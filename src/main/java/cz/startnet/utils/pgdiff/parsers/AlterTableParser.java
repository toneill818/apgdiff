/**
 * Copyright 2006 StartNet s.r.o.
 *
 * Distributed under MIT license
 */
package cz.startnet.utils.pgdiff.parsers;

import cz.startnet.utils.pgdiff.schema.PgColumn;
import cz.startnet.utils.pgdiff.schema.PgConstraint;
import cz.startnet.utils.pgdiff.schema.PgDatabase;
import cz.startnet.utils.pgdiff.schema.PgSchema;
import cz.startnet.utils.pgdiff.schema.PgSequence;
import cz.startnet.utils.pgdiff.schema.PgTable;
import cz.startnet.utils.pgdiff.schema.PgView;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses ALTER TABLE statements.
 *
 * @author fordfrog
 */
public class AlterTableParser {

    /**
     * Creates a new instance of AlterTableParser.
     */
    private AlterTableParser() {
    }

    /**
     * Parses ALTER TABLE statement.
     *
     * @param database database
     * @param statement ALTER TABLE statement
     */
    public static void parse(final PgDatabase database,
            final String statement) {
        final Parser parser = new Parser(statement);
        parser.expect("ALTER", "TABLE");
        parser.expectOptional("ONLY");

        final String tableName = parser.parseIdentifier();
        final String schemaName =
                ParserUtils.getSchemaName(tableName, database);
        final PgSchema schema = database.getSchema(schemaName);
        final String objectName = ParserUtils.getObjectName(tableName);

        final PgTable table = schema.getTable(objectName);

        if (table == null) {
            final PgView view = schema.getView(objectName);

            if (view != null) {
                parseView(parser, view);
                return;
            }

            final PgSequence sequence = schema.getSequence(objectName);

            if (sequence != null) {
                parseSequence(parser, sequence);
                return;
            }
        }

        while (!parser.expectOptional(";")) {
            if (parser.expectOptional("ALTER")) {
                parseAlterColumn(parser, table);
            } else if (parser.expectOptional("CLUSTER", "ON")) {
                table.setClusterIndexName(
                        ParserUtils.getObjectName(parser.parseIdentifier()));
            } else if (parser.expectOptional("OWNER", "TO")) {
                // we do not parse this one so we just consume the expression
                parser.getExpression();
            } else if (parser.expectOptional("ADD")) {
                if (parser.expectOptional("FOREIGN", "KEY")) {
                    parseAddForeignKey(parser, table);
                } else if (parser.expectOptional("CONSTRAINT")) {
                    parseAddConstraint(parser, table);
                } else {
                    parser.throwUnsupportedCommand();
                }
            } else if (parser.expectOptional("ENABLE")) {
                parseEnable(parser);
            } else if (parser.expectOptional("DISABLE")) {
                parseDisable(parser);
            } else {
                parser.throwUnsupportedCommand();
            }

            if (parser.expectOptional(";")) {
                break;
            } else {
                parser.expect(",");
            }
        }
    }

    /**
     * Parses ENABLE statements.
     *
     * @param parser parser
     */
    private static void parseEnable(final Parser parser) {
        if (parser.expectOptional("REPLICA")) {
            if (parser.expectOptional("TRIGGER")) {
                parser.parseIdentifier();
            } else if (parser.expectOptional("RULE")) {
                parser.parseIdentifier();
            } else {
                parser.throwUnsupportedCommand();
            }
        } else if (parser.expectOptional("ALWAYS")) {
            if (parser.expectOptional("TRIGGER")) {
                parser.parseIdentifier();
            } else if (parser.expectOptional("RULE")) {
                parser.parseIdentifier();
            } else {
                parser.throwUnsupportedCommand();
            }
        }
    }

    /**
     * Parses DISABLE statements.
     *
     * @param parser parser
     */
    private static void parseDisable(final Parser parser) {
        if (parser.expectOptional("TRIGGER")) {
            parser.parseIdentifier();
        } else if (parser.expectOptional("RULE")) {
            parser.parseIdentifier();
        } else {
            parser.throwUnsupportedCommand();
        }
    }

    /**
     * Parses ADD CONSTRAINT action.
     *
     * @param parser parser
     * @param table pg table
     */
    private static void parseAddConstraint(final Parser parser,
            final PgTable table) {
        final String constraintName =
                ParserUtils.getObjectName(parser.parseIdentifier());
        final PgConstraint constraint = new PgConstraint(constraintName);
        table.addConstraint(constraint);
        constraint.setDefinition(parser.getExpression());
        constraint.setTableName(table.getName());
    }

    /**
     * Parses ALTER COLUMN action.
     *
     * @param parser parser
     * @param table pg table
     */
    private static void parseAlterColumn(final Parser parser,
            final PgTable table) {
        parser.expectOptional("COLUMN");

        final String columnName =
                ParserUtils.getObjectName(parser.parseIdentifier());

        if (parser.expectOptional("SET")) {
            if (parser.expectOptional("STATISTICS")) {
                final PgColumn column = table.getColumn(columnName);
                column.setStatistics(parser.parseInteger());
            } else if (parser.expectOptional("DEFAULT")) {
                final String defaultValue = parser.getExpression();

                if (table.containsColumn(columnName)) {
                    final PgColumn column = table.getColumn(columnName);
                    column.setDefaultValue(defaultValue);
                } else {
                    throw new ParserException("Cannot find column '"
                            + columnName + " 'in table '" + table.getName()
                            + "'");
                }
            } else if (parser.expectOptional("STORAGE")) {
                final PgColumn column = table.getColumn(columnName);

                if (parser.expectOptional("PLAIN")) {
                    column.setStorage("PLAIN");
                } else if (parser.expectOptional("EXTERNAL")) {
                    column.setStorage("EXTERNAL");
                } else if (parser.expectOptional("EXTENDED")) {
                    column.setStorage("EXTENDED");
                } else if (parser.expectOptional("MAIN")) {
                    column.setStorage("MAIN");
                } else {
                    parser.throwUnsupportedCommand();
                }
            } else {
                parser.throwUnsupportedCommand();
            }
        } else {
            parser.throwUnsupportedCommand();
        }
    }

    /**
     * Parses ADD FOREIGN KEY action.
     *
     * @param parser parser
     * @param table pg table
     */
    private static void parseAddForeignKey(final Parser parser,
            final PgTable table) {
        final List<String> columnNames = new ArrayList<String>(1);
        parser.expect("(");

        while (!parser.expectOptional(")")) {
            columnNames.add(
                    ParserUtils.getObjectName(parser.parseIdentifier()));

            if (parser.expectOptional(")")) {
                break;
            } else {
                parser.expect(",");
            }
        }

        final String constraintName = ParserUtils.generateName(
                table.getName() + "_", columnNames, "_fkey");
        final PgConstraint constraint =
                new PgConstraint(constraintName);
        table.addConstraint(constraint);
        constraint.setDefinition(parser.getExpression());
        constraint.setTableName(table.getName());
    }

    /**
     * Parses ALTER TABLE view.
     * 
     * @param parser parser
     * @param view view
     */
    private static void parseView(final Parser parser, final PgView view) {
        while (!parser.expectOptional(";")) {
            if (parser.expectOptional("ALTER")) {
                parser.expectOptional("COLUMN");

                final String columnName =
                        ParserUtils.getObjectName(parser.parseIdentifier());

                if (parser.expectOptional("SET", "DEFAULT")) {
                    final String expression = parser.getExpression();
                    view.addColumnDefaultValue(columnName, expression);
                } else if (parser.expectOptional("DROP", "DEFAULT")) {
                    view.removeColumnDefaultValue(columnName);
                } else {
                    parser.throwUnsupportedCommand();
                }
            } else if (parser.expectOptional("OWNER", "TO")) {
                // we do not support OWNER TO so just consume the rest
                parser.getExpression();
            } else {
                parser.throwUnsupportedCommand();
            }
        }
    }

    /**
     * Parses ALTER TABLE sequence.
     *
     * @param parser parser
     * @param sequence sequence
     */
    private static void parseSequence(final Parser parser,
            final PgSequence sequence) {
        while (!parser.expectOptional(";")) {
            if (parser.expectOptional("OWNER", "TO")) {
                // we do not support OWNER TO so just consume the rest
                parser.getExpression();
            } else {
                parser.throwUnsupportedCommand();
            }
        }
    }
}
