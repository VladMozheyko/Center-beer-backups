package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.dto.MissingColumnWithDefault;
import com.dentapinos.dataguard.dto.MissingColumnsAnalysisDto;
import com.dentapinos.dataguard.dto.SchemaCompatibilityAnalysisDto;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис для анализа совместимости схемы резервной копии с целевой базой данных.
 * <p>
 * Предоставляет методы для проверки совместимости структуры таблиц резервной копии
 * со структурой существующей базы данных, а также для обработки несоответствий
 * в соответствии с заданной политикой восстановления ({@link com.dentapinos.dataguard.enums.policy.SchemaPolicy}).
 * </p>
 *
 * @see DatabaseMetadataReader Сервис для чтения метаданных базы данных
 * @see com.dentapinos.dataguard.enums.policy.SchemaPolicy Политика обработки несоответствий схемы
 * @see RestorePolicy Политика восстановления, включающая настройки схемы
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaCompatibilityService {

    private final DatabaseMetadataReader metadataService;
    private final DefaultFunctionResolver functionResolver;

    /**
     * Читает текущую схему целевой БД для указанных таблиц.
     * Публичный метод для использования из других сервисов.
     */
    public SchemaMeta getCurrentSchema(
            DbCredentials dbCredentials,
            String targetDatabase,
            List<String> tableNames
    ) {
        return metadataService.readSchema(dbCredentials, targetDatabase, tableNames);
    }

    /**
     * Анализирует совместимость схемы резервной копии с целевой базой данных.
     * <p>
     * Выполняет сравнение таблиц и колонок между резервной копией и целевой базой данных,
     * определяя отсутствующие таблицы, колонки и избыточные колонки.
     * </p>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param backup            Объект резервной копии с метаданными схемы
     * @param targetDatabase    Имя целевой базы данных для проверки совместимости
     * @return {@link SchemaCompatibilityAnalysisDto} с результатами анализа:
     *         <ul>
     *           <li>compatibleStrict — true, если схемы идентичны (без отсутствующих и избыточных колонок)</li>
     *           <li>compatibleRelaxed — true, если все таблицы и колонки из бэкапа существуют в целевой БД</li>
     *         </ul>
     * @throws IllegalStateException если произошла ошибка при чтении метаданных целевой базы данных
     */
    public SchemaCompatibilityAnalysisDto analyzeCompatibility(
            DbCredentials dbCredentials,
            BackupFile backup,
            String targetDatabase
    ) {
        SchemaMeta backupSchema = backup.schema();

        List<String> backupTableNames = backupSchema.tables().stream()
                .map(TableMeta::name)
                .toList();

        SchemaMeta currentSchema = metadataService.readSchema(
                dbCredentials,
                targetDatabase,
                backupTableNames
        );

        Map<String, TableMeta> currentByName = currentSchema.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        List<String> missingTables = new ArrayList<>();
        List<String> missingColumns = new ArrayList<>();
        List<String> extraColumns = new ArrayList<>();

        for (TableMeta backupTable : backupSchema.tables()) {
            String tableName = backupTable.name();
            TableMeta currentTable = currentByName.get(tableName);

            if (currentTable == null) {
                missingTables.add(tableName);
            } else {
                analyzeTableSchema(backupTable, currentTable, missingColumns, extraColumns);
            }
        }

        boolean compatibleStrict = missingTables.isEmpty() && missingColumns.isEmpty() && extraColumns.isEmpty();
        boolean compatibleRelaxed = missingTables.isEmpty() && missingColumns.isEmpty();

        return new SchemaCompatibilityAnalysisDto(
                compatibleStrict,
                compatibleRelaxed,
                new ArrayList<>(),
                missingTables,
                List.of()
        );
    }

    /**
     * Внутренний метод для анализа различий в схеме отдельной таблицы.
     * <p>
     * Сравнивает структуру таблицы из резервной копии с таблицей в целевой базе данных,
     * собирая списки отсутствующих и избыточных колонок.
     * </p>
     *
     * @param backupTable        Таблица из резервной копии
     * @param currentTable       Соответствующая таблица в целевой базе данных
     * @param missingColumns     Список для добавления имен колонок, отсутствующих в целевой БД (формат: "table.column")
     * @param extraColumns       Список для добавления имен избыточных колонок (формат: "table.column")
     */
    private void analyzeTableSchema(TableMeta backupTable, TableMeta currentTable,
                                    List<String> missingColumns, List<String> extraColumns) {
        String tableName = backupTable.name();

        Map<String, ColumnMeta> backupCols = backupTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));
        Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));

        for (String colName : backupCols.keySet()) {
            if (!currentCols.containsKey(colName)) {
                missingColumns.add(tableName + "." + colName);
            }
        }

        for (String colName : currentCols.keySet()) {
            if (!backupCols.containsKey(colName)) {
                extraColumns.add(tableName + "." + colName);
            }
        }
    }

    /**
     * Проверяет или корректирует схему для импорта данных.
     * <p>
     * Выполняет аналогичный анализ совместимости, но с последующим выполнением действий
     * в соответствии с заданной политикой восстановления: бросает исключение, логирует предупреждение
     * или создает отсутствующие объекты.
     * </p>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param backup            Объект резервной копии с метаданными схемы
     * @param targetDatabase    Имя целевой базы данных для импорта
     * @param policy            Политика восстановления с настройками обработки несоответствий
     * @throws IllegalStateException если политикой STRICT_SCHEMA обнаружено несоответствие схемы
     */
    public void validateOrAdjustForImport(
            DbCredentials dbCredentials,
            BackupFile backup,
            String targetDatabase,
            RestorePolicy policy
    ) {
        SchemaMeta backupSchema = backup.schema();

        List<String> backupTableNames = backupSchema.tables().stream()
                .map(TableMeta::name)
                .toList();

        SchemaMeta currentSchema = metadataService.readSchema(
                dbCredentials,
                targetDatabase,
                backupTableNames
        );

        Map<String, TableMeta> currentByName = currentSchema.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        for (TableMeta backupTable : backupSchema.tables()) {
            String tableName = backupTable.name();
            TableMeta currentTable = currentByName.get(tableName);

            if (currentTable == null) {
                handleMissingTable(dbCredentials, targetDatabase, backupTable, policy);
            } else {
                handleExistingTableSchemaDiff(dbCredentials, targetDatabase, backupTable, currentTable, policy);
            }
        }
    }

    /**
     * Обрабатывает случай отсутствия таблицы в целевой базе данных.
     * <p>
     * Выполняет действия в соответствии с {@link com.dentapinos.dataguard.enums.policy.SchemaPolicy}:
     * <ul>
     *   <li>STRICT_SCHEMA — бросает исключение</li>
     *   <li>RELAXED_SCHEMA — логирует предупреждение (данные будут пропущены)</li>
     *   <li>AUTO_CREATE_TABLES — логирует информацию о необходимости создания таблицы</li>
     * </ul>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param targetDatabase    Имя целевой базы данных
     * @param backupTable       Метаданные таблицы из резервной копии
     * @param policy            Политика восстановления
     * @throws IllegalStateException если политика STRICT_SCHEMA и таблица не найдена
     */
    private void handleMissingTable(
            DbCredentials dbCredentials,
            String targetDatabase,
            TableMeta backupTable,
            RestorePolicy policy
    ) {
        String tableName = backupTable.name();
        switch (policy.schemaPolicy()) {
            case STRICT_SCHEMA -> throw new IllegalStateException(
                    "Таблица " + tableName + " из резервной копии не найдена в целевой базе данных " + targetDatabase);
            case RELAXED_SCHEMA -> // просто логируем — эту таблицу потом можно будет пропустить при импорте
                // (в importData проверим наличие таблицы, и если её нет — пропустим данные)
                    log.warn("Таблица {} из резервной копии не найдена в целевой БД {}, данные будут пропущены", tableName, targetDatabase);
            case AUTO_CREATE_TABLES -> log.debug("Таблица {} не найдена в целевой БД {}, создаём на основе схемы из резервной копии", tableName, targetDatabase);
        }
    }

    /**
     * Обрабатывает различия в схеме существующей таблицы.
     * <p>
     * Сравнивает колонки таблицы из резервной копии с колонками в целевой базе данных
     * и выполняет действия в соответствии с {@link com.dentapinos.dataguard.enums.policy.SchemaPolicy}:
     * <ul>
     *   <li>STRICT_SCHEMA — бросает исключение при любом несоответствии</li>
     *   <li>RELAXED_SCHEMA — логирует предупреждения, но позволяет продолжить</li>
     *   <li>AUTO_CREATE_TABLES — логирует информацию о действиях по корректировке</li>
     * </ul>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param targetDatabase    Имя целевой базы данных
     * @param backupTable       Метаданные таблицы из резервной копии
     * @param currentTable      Метаданные существующей таблицы в целевой базе данных
     * @param policy            Политика восстановления
     * @throws IllegalStateException если политикой STRICT_SCHEMA обнаружено несоответствие колонок
     */
    private void handleExistingTableSchemaDiff(
            DbCredentials dbCredentials,
            String targetDatabase,
            TableMeta backupTable,
            TableMeta currentTable,
            RestorePolicy policy
    ) {
        String tableName = backupTable.name();

        Map<String, ColumnMeta> backupCols = backupTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));
        Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));

        // Колонки, которые есть в бэкапе, но нет в целевой БД
        for (String colName : backupCols.keySet()) {
            if (!currentCols.containsKey(colName)) {
                switch (policy.schemaPolicy()) {
                    case STRICT_SCHEMA -> throw new IllegalStateException(
                            "Колонка " + tableName + "." + colName + " есть в резервной копии, но отсутствует в целевой БД");
                    case RELAXED_SCHEMA -> log.warn(
                            "Колонка {}.{} есть в резервной копии, но отсутствует в целевой БД; значение будет проигнорировано при импорте",
                            tableName, colName
                    );
                    case AUTO_CREATE_TABLES -> log.debug("Добавляем отсутствующую колонку {}.{} в целевую БД {}", tableName, colName, targetDatabase);
                }
            }
        }

        // Колонки, которые есть в целевой БД, но нет в бэкапе
        for (String colName : currentCols.keySet()) {
            if (!backupCols.containsKey(colName)) {
                switch (policy.schemaPolicy()) {
                    case STRICT_SCHEMA -> throw new IllegalStateException(
                            "Колонка " + tableName + "." + colName + " есть в целевой БД, но отсутствует в резервной копии");
                    case RELAXED_SCHEMA, AUTO_CREATE_TABLES -> // Ок: при вставке они получат DEFAULT/NULL
                            log.debug(
                                    "Колонка {}.{} есть в целевой БД, но отсутствует в резервной копии; будет заполнено значением по умолчанию/NULL",
                                    tableName, colName
                            );
                }
            }
        }
    }

    /**
     * Анализирует недостающие колонки и генерирует подсказки для дефолтных значений.
     * <p>
     * Находит колонки, которые есть в целевой БД, но отсутствуют в бэкапе,
     * и формирует список с подсказками для указания дефолтных значений.
     * Пользователь может использовать эту информацию для ручной корректировки
     * бэкапа или указания дефолтных значений перед повторным восстановлением.
     * </p>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param backup            Объект резервной копии с метаданными схемы
     * @param targetDatabase    Имя целевой базы данных для анализа
     * @return {@link MissingColumnsAnalysisDto} с списком недостающих колонок и подсказками
     */
    public MissingColumnsAnalysisDto generateMissingColumnsAnalysis(
            DbCredentials dbCredentials,
            BackupFile backup,
            String targetDatabase
    ) {
        SchemaMeta backupSchema = backup.schema();

        List<String> backupTableNames = backupSchema.tables().stream()
                .map(TableMeta::name)
                .toList();

        SchemaMeta currentSchema = metadataService.readSchema(
                dbCredentials,
                targetDatabase,
                backupTableNames
        );

        Map<String, TableMeta> currentByName = currentSchema.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        List<MissingColumnWithDefault> missingColumns = new ArrayList<>();
        Map<String, Map<String, Object>> readyToUseMissingFields = new HashMap<>();

        for (TableMeta backupTable : backupSchema.tables()) {
            String tableName = backupTable.name();
            TableMeta currentTable = currentByName.get(tableName);

            if (currentTable != null) {
                analyzeMissingColumns(backupTable, currentTable, missingColumns, readyToUseMissingFields);
            }
        }

        // === Post-processing: гарантируем, что все колонки из missingColumns есть в readyToUseMissingFields ===
        int missingFromDefaults = 0;
        for (MissingColumnWithDefault mc : missingColumns) {
            Map<String, Object> tableDefaults = readyToUseMissingFields.get(mc.tableName());
            if (tableDefaults == null || !tableDefaults.containsKey(mc.columnName())) {
                log.warn("[MISSING_COLUMNS] Column '{}' in table '{}' is in missingColumns but not in readyToUseMissingFields — auto-fixing",
                        mc.columnName(), mc.tableName());
                readyToUseMissingFields
                        .computeIfAbsent(mc.tableName(), k -> new HashMap<>())
                        .put(mc.columnName(), getDefaultValueForType(mc.columnType(), mc.nullable()));
                missingFromDefaults++;
            }
        }
        if (missingFromDefaults > 0) {
            log.warn("[MISSING_COLUMNS] Fixed {} columns missing from readyToUseMissingFields", missingFromDefaults);
        }

        int tableCount = (int) missingColumns.stream()
                .map(MissingColumnWithDefault::tableName)
                .distinct()
                .count();

        String instructions = "Для каждой колонки укажите значение по умолчанию. " +
                "Если колонка nullable=true, можно указать NULL. " +
                "Если колонка NOT NULL без DEFAULT, необходимо указать подходящее значение. " +
                "После корректировки бэкапа выполните повторный запрос на восстановление.";

        return new MissingColumnsAnalysisDto(
                missingColumns,
                tableCount,
                missingColumns.size(),
                instructions,
                readyToUseMissingFields,
                functionResolver.getAvailableFunctions()
        );
    }

    /**
     * Внутренний метод для анализа недостающих колонок в конкретной таблице.
     *
     * @param backupTable                Таблица из резервной копии
     * @param currentTable               Соответствующая таблица в целевой базе данных
     * @param missingColumns             Список для добавления недостающих колонок
     * @param readyToUseMissingFields    Мапа для готового шаблона missingFields
     */
    private void analyzeMissingColumns(
            TableMeta backupTable,
            TableMeta currentTable,
            List<MissingColumnWithDefault> missingColumns,
            Map<String, Map<String, Object>> readyToUseMissingFields
    ) {
        String tableName = backupTable.name();

        Map<String, ColumnMeta> backupCols = backupTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));
        Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));

        // Находим колонки, которые есть в целевой БД, но нет в бэкапе
        for (String colName : currentCols.keySet()) {
            if (!backupCols.containsKey(colName)) {
                ColumnMeta currentCol = currentCols.get(colName);
                String hint = generateDefaultValueHint(tableName, colName, currentCol);
                
                missingColumns.add(new MissingColumnWithDefault(
                        tableName,
                        colName,
                        currentCol.type(),
                        currentCol.nullable(),
                        hint
                ));

                // Добавляем в готовый шаблон с пустым значением по умолчанию
                readyToUseMissingFields
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(colName, getDefaultValueForType(currentCol.type(), currentCol.nullable()));
            }
        }
    }

    /**
     * Генерирует подсказку для указания дефолтного значения колонки.
     *
     * @param tableName  Имя таблицы
     * @param colName    Имя колонки
     * @param colMeta    Метаданные колонки
     * @return Текст подсказки
     */
    private String generateDefaultValueHint(
            String tableName,
            String colName,
            ColumnMeta colMeta
    ) {
        StringBuilder hint = new StringBuilder();
        hint.append("Колонка '").append(colName).append("' типа ").append(colMeta.type());
        
        if (colMeta.autoIncrement()) {
            hint.append(", автоинкремент. Для таких колонок обычно не нужно указывать значение — они генерируются автоматически.");
        } else if (colMeta.nullable()) {
            hint.append(", может содержать NULL. Можно указать NULL или подходящее значение по умолчанию.");
        } else {
            hint.append(", NOT NULL. Необходимо указать значение по умолчанию для этой колонки.");
        }
        
        hint.append(" (таблица: ").append(tableName).append(")");
        
        return hint.toString();
    }

    /**
     * Определяет значение по умолчанию на основе типа колонки.
     *
     * @param columnType тип колонки (например, "varchar(255)", "datetime(6)", "enum('ACTIVATED','EXPIRED')")
     * @param nullable   может ли колонка содержать NULL
     * @return значение по умолчанию (пустая строка для строк, 0 для чисел, false для булевых)
     */
    private Object getDefaultValueForType(String columnType, boolean nullable) {
        if (nullable) {
            return null;
        }

        String lowerType = columnType.toLowerCase();

        // Числовые типы
        if (lowerType.startsWith("int") || lowerType.startsWith("bigint") || 
            lowerType.startsWith("smallint") || lowerType.startsWith("tinyint") ||
            lowerType.startsWith("double") || lowerType.startsWith("float") ||
            lowerType.startsWith("decimal") || lowerType.startsWith("numeric")) {
            return 0;
        }

        // Булевый тип
        if (lowerType.startsWith("bool")) {
            return false;
        }

        // Date/time типы — возвращаем плейсхолдер #now, который DefaultFunctionResolver разрешит в CURRENT_TIMESTAMP
        if (lowerType.startsWith("date") || lowerType.startsWith("time") ||
            lowerType.startsWith("datetime") || lowerType.startsWith("timestamp")) {
            return "#now";
        }

        // Enum типы - берём первый элемент
        if (lowerType.startsWith("enum")) {
            int firstParen = columnType.indexOf('(');
            if (firstParen != -1) {
                String enumValues = columnType.substring(firstParen + 1, columnType.lastIndexOf(')'));
                String[] values = enumValues.split(",");
                if (values.length > 0) {
                    // Убираем кавычки из первого значения
                    return values[0].trim().replaceAll("'", "");
                }
            }
            return "";
        }

        // Строковые типы (varchar, text, char и т.д.)
        return "";
    }
}
