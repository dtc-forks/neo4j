/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.importer;

import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.collections.impl.tuple.Tuples.pair;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.csv.reader.Configuration.COMMAS;
import static org.neo4j.importer.CsvImporter.DEFAULT_REPORT_FILE_NAME;
import static org.neo4j.internal.batchimport.Configuration.DEFAULT;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Help.Visibility.ALWAYS;
import static picocli.CommandLine.Help.Visibility.NEVER;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.collections.api.tuple.Pair;
import org.neo4j.cli.AbstractAdminCommand;
import org.neo4j.cli.Converters.ByteUnitConverter;
import org.neo4j.cli.Converters.DatabaseNameConverter;
import org.neo4j.cli.Converters.MaxOffHeapMemoryConverter;
import org.neo4j.cli.ExecutionContext;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.batchimport.IndexConfig;
import org.neo4j.internal.batchimport.input.IdType;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.io.layout.recordstorage.RecordDatabaseLayout;
import org.neo4j.kernel.database.NormalizedDatabaseName;
import org.neo4j.kernel.impl.util.Converters;
import org.neo4j.kernel.impl.util.Validators;
import org.neo4j.util.VisibleForTesting;
import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "import",
        description = "Import a collection of CSV files.",
        subcommands = {ImportCommand.Full.class, CommandLine.HelpCommand.class})
@SuppressWarnings("FieldMayBeFinal")
public class ImportCommand {

    /**
     * Arguments and logic shared between Full and Incremental import commands.
     */
    private abstract static class Base extends AbstractAdminCommand {
        /**
         * Delimiter used between files in an input group.
         */
        private static final Function<String, Character> CHARACTER_CONVERTER = new CharacterConverter();

        private static final org.neo4j.csv.reader.Configuration DEFAULT_CSV_CONFIG = COMMAS;
        private static final Configuration DEFAULT_IMPORTER_CONFIG = DEFAULT;

        private enum OnOffAuto {
            ON,
            OFF,
            AUTO
        }

        static class OnOffAutoConverter implements ITypeConverter<OnOffAuto> {
            @Override
            public OnOffAuto convert(String value) throws Exception {
                return OnOffAuto.valueOf(value.toUpperCase(Locale.ROOT));
            }
        }

        @Parameters(
                index = "0",
                converter = DatabaseNameConverter.class,
                defaultValue = DEFAULT_DATABASE_NAME,
                description = "Name of the database to import.%n"
                        + "  If the database into which you import does not exist prior to importing,%n"
                        + "  you must create it subsequently using CREATE DATABASE.")
        private NormalizedDatabaseName database;

        @Option(
                names = "--report-file",
                paramLabel = "<path>",
                defaultValue = DEFAULT_REPORT_FILE_NAME,
                description = "File in which to store the report of the csv-import.")
        private Path reportFile = Path.of(DEFAULT_REPORT_FILE_NAME);

        @Option(
                names = "--id-type",
                paramLabel = "string|integer|actual",
                defaultValue = "string",
                description = "Each node must provide a unique ID. This is used to find the "
                        + "correct nodes when creating relationships. Possible values are:%n"
                        + "  string: arbitrary strings for identifying nodes,%n"
                        + "  integer: arbitrary integer values for identifying nodes,%n"
                        + "  actual: (advanced) actual node IDs.%n"
                        + "For more information on ID handling, please see the Neo4j Manual: "
                        + "https://neo4j.com/docs/operations-manual/current/tools/import/",
                converter = IdTypeConverter.class)
        IdType idType = IdType.STRING;

        @Option(
                names = "--input-encoding",
                paramLabel = "<character-set>",
                description = "Character set that input data is encoded in.")
        private Charset inputEncoding = StandardCharsets.UTF_8;

        @Option(
                names = "--ignore-extra-columns",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description = "If unspecified columns should be ignored during the import.")
        private boolean ignoreExtraColumns;

        private static final String MULTILINE_FIELDS = "--multiline-fields";

        @Option(
                names = MULTILINE_FIELDS,
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description =
                        "Whether or not fields from an input source can span multiple lines, i.e. contain newline characters. "
                                + "Setting " + MULTILINE_FIELDS + "=true can severely degrade the performance of "
                                + "the importer. Therefore, use it with care, especially with large imports.")
        private boolean multilineFields = DEFAULT_CSV_CONFIG.multilineFields();

        @Option(
                names = "--ignore-empty-strings",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description =
                        "Whether or not empty string fields, i.e. \"\" from input source are ignored, i.e. treated as null.")
        private boolean ignoreEmptyStrings = DEFAULT_CSV_CONFIG.emptyQuotedStringsAsNull();

        @Option(
                names = "--trim-strings",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description = "Whether or not strings should be trimmed for whitespaces.")
        private boolean trimStrings = DEFAULT_CSV_CONFIG.trimStrings();

        @Option(
                names = "--legacy-style-quoting",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description = "Whether or not a backslash-escaped quote e.g. \\\" is interpreted as an inner quote.")
        private boolean legacyStyleQuoting = DEFAULT_CSV_CONFIG.legacyStyleQuoting();

        @Option(
                names = "--delimiter",
                paramLabel = "<char>",
                converter = EscapedCharacterConverter.class,
                description = "Delimiter character between values in CSV data. "
                        + "Also accepts 'TAB' and e.g. 'U+20AC' for specifying a character using Unicode.")
        private char delimiter = DEFAULT_CSV_CONFIG.delimiter();

        @Option(
                names = "--array-delimiter",
                paramLabel = "<char>",
                converter = EscapedCharacterConverter.class,
                description = "Delimiter character between array elements within a value in CSV data. "
                        + "Also accepts 'TAB' and e.g. 'U+20AC' for specifying a character using Unicode.")
        private char arrayDelimiter = DEFAULT_CSV_CONFIG.arrayDelimiter();

        @Option(
                names = "--quote",
                paramLabel = "<char>",
                converter = EscapedCharacterConverter.class,
                description =
                        "Character to treat as quotation character for values in CSV data. Quotes can be escaped as per RFC 4180 by doubling them, "
                                + "for example \"\" would be interpreted as a literal \". You cannot escape using \\.")
        private char quote = DEFAULT_CSV_CONFIG.quotationCharacter();

        @Option(
                names = "--read-buffer-size",
                paramLabel = "<size>",
                converter = ByteUnitConverter.class,
                description = "Size of each buffer for reading input data. "
                        + "It has to be at least large enough to hold the biggest single value in the input data. "
                        + "The value can be a plain number or a byte units string, e.g. 128k, 1m.")
        private long bufferSize = DEFAULT_CSV_CONFIG.bufferSize();

        @Option(
                names = "--max-off-heap-memory",
                paramLabel = "<size>",
                defaultValue = "90%",
                converter = MaxOffHeapMemoryConverter.class,
                description =
                        "Maximum memory that neo4j-admin can use for various data structures and caching to improve performance. "
                                + "Values can be plain numbers, such as 10000000, or 20G for 20 gigabytes. "
                                + "It can also be specified as a percentage of the available memory, for example 70%%.")
        private long maxOffHeapMemory;

        @Option(
                names = "--high-parallel-io",
                showDefaultValue = ALWAYS,
                paramLabel = "on|off|auto",
                defaultValue = "auto",
                converter = OnOffAutoConverter.class,
                description =
                        "Ignore environment-based heuristics and indicate if the target storage subsystem can support parallel IO with high throughput or auto detect. "
                                + " Typically this is on for SSDs, large raid arrays, and network-attached storage.")
        private OnOffAuto highIo;

        @Option(
                names = "--threads",
                paramLabel = "<num>",
                description =
                        "(advanced) Max number of worker threads used by the importer. Defaults to the number of available processors reported by the JVM. "
                                + "There is a certain amount of minimum threads needed so for that reason there is no lower bound for this "
                                + "value. For optimal performance, this value should not be greater than the number of available processors.")
        private int threads = DEFAULT_IMPORTER_CONFIG.maxNumberOfWorkerThreads();

        private static final String BAD_TOLERANCE_OPTION = "--bad-tolerance";

        @Option(
                names = BAD_TOLERANCE_OPTION,
                paramLabel = "<num>",
                description =
                        "Number of bad entries before the import is considered failed. This tolerance threshold is about relationships referring to "
                                + "missing nodes. Format errors in input data are still treated as errors.")
        private long badTolerance = 1000;

        public static final String SKIP_BAD_ENTRIES_LOGGING = "--skip-bad-entries-logging";

        @Option(
                names = SKIP_BAD_ENTRIES_LOGGING,
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description = "Whether or not to skip logging bad entries detected during import.")
        private boolean skipBadEntriesLogging;

        @Option(
                names = "--skip-bad-relationships",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description =
                        "Whether or not to skip importing relationships that refer to missing node IDs, i.e. either start or end node ID/group referring "
                                + "to a node that was not specified by the node input data. Skipped relationships will be logged, containing at most the number of entities "
                                + "specified by " + BAD_TOLERANCE_OPTION + ", unless otherwise specified by the "
                                + SKIP_BAD_ENTRIES_LOGGING + " option.")
        private boolean skipBadRelationships;

        @Option(
                names = "--strict",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                description =
                        "Whether or not the lookup of nodes referred to from relationships needs to be checked strict. "
                                + "If disabled, most but not all relationships referring to non-existent nodes will be detected. "
                                + "If enabled all those relationships will be found but at the cost of lower performance.")
        private boolean strict = false;

        @Option(
                names = "--skip-duplicate-nodes",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description =
                        "Whether or not to skip importing nodes that have the same ID/group. In the event of multiple nodes within the same group having "
                                + "the same ID, the first encountered will be imported, whereas consecutive such nodes will be skipped. Skipped nodes will be logged, "
                                + "containing at most the number of entities specified by " + BAD_TOLERANCE_OPTION
                                + ", unless otherwise specified by the " + SKIP_BAD_ENTRIES_LOGGING + " option.")
        private boolean skipDuplicateNodes;

        @Option(
                names = "--normalize-types",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description =
                        "Whether or not to normalize property types to Cypher types, e.g. 'int' becomes 'long' and 'float' becomes 'double'.")
        private boolean normalizeTypes = true;

        @Option(
                names = "--nodes",
                required = true,
                arity = "1..*",
                converter = NodeFilesConverter.class,
                paramLabel = "[<label>[:<label>]...=]<files>",
                description =
                        "Node CSV header and data. Multiple files will be logically seen as one big file from the perspective of the importer. The first "
                                + "line must contain the header. Multiple data sources like these can be specified in one import, where each data source has its "
                                + "own header. Files can also be specified using regular expressions.")
        private List<NodeFilesGroup> nodes;

        @Option(
                names = "--relationships",
                arity = "1..*",
                converter = RelationsipFilesConverter.class,
                showDefaultValue = NEVER,
                paramLabel = "[<type>=]<files>",
                description =
                        "Relationship CSV header and data. Multiple files will be logically seen as one big file from the perspective of the importer. "
                                + "The first line must contain the header. Multiple data sources like these can be specified in one import, where each data source has "
                                + "its own header. Files can also be specified using regular expressions.")
        private List<RelationshipFilesGroup> relationships = new ArrayList<>();

        @Option(
                names = "--auto-skip-subsequent-headers",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description =
                        "Automatically skip accidental header lines in subsequent files in file groups with more than one file.")
        private boolean autoSkipHeaders;

        Base(ExecutionContext ctx) {
            super(ctx);
        }

        @Override
        protected Optional<String> commandConfigName() {
            return Optional.of("database-import");
        }

        protected void doExecute(
                boolean incremental, CsvImporter.IncrementalStage mode, String format, boolean overwriteDestination) {
            try {
                final var databaseConfig = loadNeo4jConfig(format);
                Neo4jLayout neo4jLayout = Neo4jLayout.of(databaseConfig);
                final var databaseLayout = RecordDatabaseLayout.of(
                        neo4jLayout, database.name()); // Right now we only support Record storage for import command
                final var csvConfig = csvConfiguration();
                final var importConfig = importConfiguration();

                final var importerBuilder = CsvImporter.builder()
                        .withDatabaseLayout(databaseLayout)
                        .withDatabaseConfig(databaseConfig)
                        .withFileSystem(ctx.fs())
                        .withStdOut(ctx.out())
                        .withStdErr(ctx.err())
                        .withCsvConfig(csvConfig)
                        .withImportConfig(importConfig)
                        .withIdType(idType)
                        .withInputEncoding(inputEncoding)
                        .withReportFile(reportFile.toAbsolutePath())
                        .withIgnoreExtraColumns(ignoreExtraColumns)
                        .withBadTolerance(badTolerance)
                        .withSkipBadRelationships(skipBadRelationships)
                        .withSkipDuplicateNodes(skipDuplicateNodes)
                        .withSkipBadEntriesLogging(skipBadEntriesLogging)
                        .withSkipBadRelationships(skipBadRelationships)
                        .withNormalizeTypes(normalizeTypes)
                        .withVerbose(verbose)
                        .withAutoSkipHeaders(autoSkipHeaders)
                        .withForce(overwriteDestination)
                        .withIncremental(incremental);
                if (incremental) {
                    importerBuilder.withIncrementalStage(mode);
                }

                nodes.forEach(n -> importerBuilder.addNodeFiles(n.key, n.files));

                relationships.forEach(n -> importerBuilder.addRelationshipFiles(n.key, n.files));

                final var importer = importerBuilder.build();
                importer.doImport();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @VisibleForTesting
        Config loadNeo4jConfig(String format) {
            Config.Builder builder = createPrefilledConfigBuilder();
            if (StringUtils.isNotEmpty(format)) {
                builder.set(GraphDatabaseSettings.db_format, format);
            }
            return builder.build();
        }

        private org.neo4j.csv.reader.Configuration csvConfiguration() {
            return DEFAULT_CSV_CONFIG.toBuilder()
                    .withDelimiter(delimiter)
                    .withArrayDelimiter(arrayDelimiter)
                    .withQuotationCharacter(quote)
                    .withMultilineFields(multilineFields)
                    .withEmptyQuotedStringsAsNull(ignoreEmptyStrings)
                    .withTrimStrings(trimStrings)
                    .withLegacyStyleQuoting(legacyStyleQuoting)
                    .withBufferSize(toIntExact(bufferSize))
                    .build();
        }

        private org.neo4j.internal.batchimport.Configuration importConfiguration() {
            return new Configuration.Overridden(Configuration.defaultConfiguration()) {
                @Override
                public int maxNumberOfWorkerThreads() {
                    return threads;
                }

                @Override
                public long maxOffHeapMemory() {
                    return maxOffHeapMemory;
                }

                @Override
                public boolean highIO() {
                    // super.highIO will look at the device and make a decision
                    return highIo == OnOffAuto.AUTO ? super.highIO() : highIo == OnOffAuto.ON;
                }

                @Override
                public IndexConfig indexConfig() {
                    return IndexConfig.create().withLabelIndex().withRelationshipTypeIndex();
                }

                @Override
                public boolean strictNodeCheck() {
                    return strict;
                }
            };
        }

        static class EscapedCharacterConverter implements ITypeConverter<Character> {
            @Override
            public Character convert(String value) {
                return CHARACTER_CONVERTER.apply(value);
            }
        }

        static class NodeFilesConverter implements ITypeConverter<NodeFilesGroup> {
            @Override
            public NodeFilesGroup convert(String value) {
                try {
                    return parseNodeFilesGroup(value);
                } catch (Exception e) {
                    throw new CommandLine.TypeConversionException(format("Invalid nodes file: %s (%s)", value, e));
                }
            }
        }

        static class RelationsipFilesConverter implements ITypeConverter<InputFilesGroup<String>> {
            @Override
            public InputFilesGroup<String> convert(String value) {
                try {
                    return parseRelationshipFilesGroup(value);
                } catch (Exception e) {
                    throw new CommandLine.TypeConversionException(
                            format("Invalid relationships file: %s (%s)", value, e));
                }
            }
        }

        static class IdTypeConverter implements CommandLine.ITypeConverter<IdType> {
            @Override
            public IdType convert(String in) {
                try {
                    return IdType.valueOf(in.toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    throw new CommandLine.TypeConversionException(format("Invalid id type: %s (%s)", in, e));
                }
            }
        }
    }

    @Command(name = "full", description = "Initial import into a non-existent empty database.")
    public static class Full extends Base {
        @Option(
                names = "--format",
                showDefaultValue = NEVER,
                required = false,
                description = "Name of database format. Imported database will be created of the specified format "
                        + "or use format from configuration if not specified.")
        private String format;

        // Was force
        @Option(
                names = "--overwrite-destination",
                arity = "0..1",
                showDefaultValue = ALWAYS,
                paramLabel = "true|false",
                fallbackValue = "true",
                description = "Delete any existing database files prior to the import.")
        private boolean overwriteDestination;

        public Full(ExecutionContext ctx) {
            super(ctx);
        }

        @Override
        public void execute() throws Exception {
            doExecute(false, null, format, overwriteDestination);
        }
    }

    @Command(name = "incremental", description = "Incremental import into an existing database.")
    public static class Incremental extends Base {
        @Option(
                names = "--stage",
                paramLabel = "all|prepare|build|merge",
                description = "Stage of incremental import. "
                        + "For incremental import into an existing database use 'all' (which requires "
                        + "the database to be stopped). For semi-online incremental import run 'prepare' (on "
                        + "a stopped database) followed by 'build' (on a potentially running database) and "
                        + "finally 'merge' (on a stopped database).",
                converter = StageConverter.class)
        CsvImporter.IncrementalStage stage = CsvImporter.IncrementalStage.all;

        @Option(names = "--force", required = true, description = "Confirm incremental import by setting this flag.")
        boolean forced;

        public Incremental(ExecutionContext ctx) {
            super(ctx);
        }

        @Override
        public void execute() throws Exception {
            if (!forced) {
                System.err.println(
                        "ERROR: Incremental import needs to be used with care. Please confirm by specifying --force.");
                throw new IllegalArgumentException("Missing force");
            }
            doExecute(true, stage, null, false);
        }

        static class StageConverter implements CommandLine.ITypeConverter<CsvImporter.IncrementalStage> {
            @Override
            public CsvImporter.IncrementalStage convert(String in) {
                in = switch (in) {
                    case "1" -> "prepare";
                    case "2" -> "build";
                    case "3" -> "merge";
                    default -> in.toLowerCase();};
                try {
                    return CsvImporter.IncrementalStage.valueOf(in);

                } catch (Exception e) {
                    throw new CommandLine.TypeConversionException(format("Invalid stage: %s (%s)", in, e));
                }
            }
        }
    }

    private static final String MULTI_FILE_DELIMITER = ",";

    static class NodeFilesGroup extends InputFilesGroup<Set<String>> {
        NodeFilesGroup(Set<String> key, Path[] files) {
            super(key, files);
        }
    }

    static class RelationshipFilesGroup extends InputFilesGroup<String> {
        RelationshipFilesGroup(String key, Path[] files) {
            super(key, files);
        }
    }

    abstract static class InputFilesGroup<T> {
        final T key;
        final Path[] files;

        InputFilesGroup(T key, Path[] files) {
            this.key = key;
            this.files = files;
        }
    }

    @VisibleForTesting
    static RelationshipFilesGroup parseRelationshipFilesGroup(String str) {
        final var p = parseInputFilesGroup(str, String::trim);
        return new RelationshipFilesGroup(p.getOne(), p.getTwo());
    }

    @VisibleForTesting
    static NodeFilesGroup parseNodeFilesGroup(String str) {
        final var p = parseInputFilesGroup(str, s -> stream(s.split(":"))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(toSet()));
        return new NodeFilesGroup(p.getOne(), p.getTwo());
    }

    private static <T> Pair<T, Path[]> parseInputFilesGroup(String str, Function<String, ? extends T> keyParser) {
        final var i = str.indexOf('=');
        if (i < 0) {
            return pair(keyParser.apply(""), parseFilesList(str));
        }
        if (i == 0 || i == str.length() - 1) {
            throw new IllegalArgumentException("illegal `=` position: " + str);
        }
        final var keyStr = str.substring(0, i);
        final var filesStr = str.substring(i + 1);
        final var key = keyParser.apply(keyStr);
        final var files = parseFilesList(filesStr);
        return pair(key, files);
    }

    private static Path[] parseFilesList(String str) {
        final var converter = Converters.regexFiles(true);
        return Converters.toFiles(MULTI_FILE_DELIMITER, s -> {
                    Validators.REGEX_FILE_EXISTS.validate(s);
                    return converter.apply(s);
                })
                .apply(str);
    }

    @Option(
            names = {"-h", "--help"},
            usageHelp = true,
            description = "Show this help message and exit.")
    private boolean helpRequested;
}
