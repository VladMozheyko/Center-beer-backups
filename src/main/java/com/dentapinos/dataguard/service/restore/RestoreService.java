package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.exception.*;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.report.RestoreSummary;
import com.dentapinos.dataguard.service.restore.order.RestoreOrderService;
import com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy;
import com.dentapinos.dataguard.service.restore.strategy.RestoreStrategyFactory;
import com.dentapinos.dataguard.storage.BackupFileNamingService;
import com.dentapinos.dataguard.storage.BackupFileReader;
import com.dentapinos.dataguard.storage.BackupStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <p>Сервис восстановления данных из бэкапа в уже существующую базу данных.
 * </p>
 * Основной публичный метод:
 *  - загружает файл бэкапа,
 *  - определяет политику восстановления,
 *  - проверяет и при необходимости подстраивает схему целевой БД,
 *  - импортирует данные,
 *  - формирует отчёт (RestoreReport) с итоговой статистикой.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestoreService {

    private final RestorePolicyService restorePolicyService;
    private final RestoreStrategyFactory restoreStrategyFactory;
    private final BackupStorage backupStorage;
    private final BackupFileNamingService fileNameParser;
    private final BackupFileReader backupFileReader;
    private final SchemaCompatibilityService schemaCompatibilityService;
    private final RestoreOrderService restoreOrderService;
    private final DefaultFunctionResolver functionResolver;

    /**
     * <p>Восстанавливает данные в существующую базу данных из указанного бэкапа.
     * </p>
     * Шаги:
     *  1) Фиксация времени начала.
     *  2) Загрузка полного бэкапа.
     *  3) Определение полит��ки восстановления в зависимости от режима.
     *  4) Проверка / корректировка схемы целевой БД под импорт.
     *  5) Фильтрация таблиц (если указаны).
     *  6) Определение порядка восстановления таблиц на основе графа зависимостей.
     *  7) Импорт данных (таблицы, строки) с подсчётом статистики.
     *  8) Формирование сводного отчёта и вычисление статуса восстановления.
     *
     * @param dbCredentials   учетные данные подключения к целевой БД
     * @param tier            уровень (слой) бэкапа (например, полный/инкрементальный)
     * @param backupName      имя бэкап‑файла
     * @param targetDatabase  имя целевой базы данных, куда идёт восстановление
     * @param mode            режим восстановления (например, MERGE, OVERWRITE и т.п.)
     * @param tables          список таблиц для восстановления (если null/пусто — восстанавливаются все)
     * @return RestoreReport -подробный отчёт о выполненном восстановлении
     */
    public RestoreReport restoreToExistingDatabase(
            DbCredentials dbCredentials,
            BackupTier tier,
            String backupName,
            String targetDatabase,
            RestoreMode mode,
            List<String> tables
    ) {
        return restoreToExistingDatabase(dbCredentials, tier, backupName, targetDatabase, mode, tables, null);
    }

    /**
     * <p>Восстанавливает данные в существующую базу данных из указанного бэкапа
     * с применением значений по умолчанию для недостающих колонок.
     * </p>
     * <p>
     * Метод аналогичен {@link #restoreToExistingDatabase(DbCredentials, BackupTier, String, String, RestoreMode, List)},
     * но дополнительно применяет значения из {@code missingFields} для колонок,
     * которые есть в целевой БД, но отсутствуют в бэкапе.
     * </p>
     *
     * @param dbCredentials   учетные данные подключения к целевой БД
     * @param tier            уровень (слой) бэкапа
     * @param backupName      имя бэкап‑файла
     * @param targetDatabase  имя целевой базы данных
     * @param mode            режим восстановления
     * @param tables          список таблиц для восстановления
     * @param missingFields   мапа {таблица -> {колонка -> значение_по_умолчанию}}
     * @return RestoreReport - подробный отчёт о выполненном восстановлении
     */
    public RestoreReport restoreToExistingDatabase(
            DbCredentials dbCredentials,
            BackupTier tier,
            String backupName,
            String targetDatabase,
            RestoreMode mode,
            List<String> tables,
            Map<String, Map<String, Object>> missingFields
    ) {

        // === 3. Определяем политику восстановления на основе режима (RestoreMode) ===
        RestorePolicy policy;
        try {
            policy = restorePolicyService.policyFor(mode);
        } catch (InvalidRestoreModeException e) {
            String errorMsg = "Неизвестный режим восстановления: " + mode;
            log.error("[RESTORE_POLICY] {}", errorMsg, e);
            throw e;
        }

        // === 1. Фиксируем время начала операции восстановления ===
        Instant startedAt = Instant.now();

        // === 2. Загружаем полный бэкап как доменную сущность BackupFile ===
        String dirDBName = fileNameParser.parseDatabaseName(backupName);
        BackupFile backupFile;
        try (InputStream is = backupStorage.load(tier, dirDBName, backupName)) {
            backupFile = backupFileReader.readBackupFile(is);
        } catch (BackupFileReadingException e) {
            // Ошибка в ZIP-архиве (отсутствует backup.json или поврежден ZIP)
            String errorMessage = "Восстановление невозможно: ZIP-архив поврежден или не содержит обязательную запись backup.json. Бэкап: '" + backupName + "'";
            log.error(errorMessage, e);
            throw new RestoreZipException(errorMessage);
        } catch (BackupFileNotFoundException e) {
            // Перехватываем BackupFileNotFoundException и превращаем в BackupStorageException
            // для единообразия обработки ошибок хранилища
            String errorMessage = "Бэкап не найден: '" + backupName + "'";
            log.error(errorMessage, e);
            throw new BackupStorageException(errorMessage, e);
        } catch (IOException e) {
            // Для других ошибок ввода-вывода при загрузке используем BackupStorageException
            String errorMessage = "Ошибка загрузки бэкапа '" + backupName + "' из хранилища";
            log.error(errorMessage, e);
            throw new BackupStorageException(errorMessage, e);
        }

        // === 3.5. Фильтрация таблиц (если указаны) ===
        BackupFile filteredBackup = TableFilter.filterTables(backupFile, tables);
        log.debug("[TABLE_FILTER] Фильтрация таблиц: originalTables={}, filteredTables={}",
                backupFile.data().size(), filteredBackup.data().size());

        // === 3.55. Автозаполнение недостающих колонок (если missingFields null или неполный) ===
        Map<String, Map<String, Object>> resolvedMissingFields = resolveMissingFields(
                dbCredentials, targetDatabase, filteredBackup, missingFields
        );

        // === 3.6. Применение значений по умолчанию для недостающих колонок ===
        BackupFile backupWithDefaults = applyMissingFieldsDefaults(filteredBackup, resolvedMissingFields);
        if (resolvedMissingFields != null && !resolvedMissingFields.isEmpty()) {
            log.info("[MISSING_FIELDS] Применены значения по умолчанию для {} таблиц", resolvedMissingFields.size());
        }

        // === 3.5. Проверка совместимости схемы бэкапа и целевой БД ===
        log.info("[SCHEMA_CHECK] Проверка совместимости схемы бэкапа '{}' с целевой БД '{}'", backupFile.database(), targetDatabase);
        try {
            schemaCompatibilityService.validateOrAdjustForImport(dbCredentials, filteredBackup, targetDatabase, policy);
        } catch (IllegalStateException e) {
            String errorMsg = "Несовместимость схемы при восстановлении бэкапа '" + backupName + "' в БД '" + targetDatabase + "': " + e.getMessage();
            log.error("[SCHEMA_CHECK] {}", errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }

        // === 4. Определяем порядок восстановления таблиц ===
        log.info("[RESTORE_ORDER] Определение порядка восстановления таблиц");
        List<String> restoreOrder;
        try {
            // Определяем порядок на основе внешних ключей (graph-based)
            restoreOrder = restoreOrderService.determineRestoreOrder(
                    dbCredentials,
                    targetDatabase,
                    tables // используем отфильтрованный список таблиц
            );
        } catch (Exception e) {
            log.warn("[RESTORE_ORDER] Ошибка при определении порядка восстановления: {}", e.getMessage());
            log.warn("[RESTORE_ORDER] Используем порядок таблиц из бэкапа как fallback");
            restoreOrder = null; // Используем порядок из бэкапа
        }
        log.debug("[RESTORE_ORDER] Порядок восстановления: {}", restoreOrder != null ? restoreOrder : "(из бэкапа)");

        // === 5. Импорт данных из бэкапа в целевую базу ===
        // RestoreStats будет накапливать статистику:
        //  - сколько таблиц обработано / пропущено / с ошибками
        //  - сколько строк вставлено / обновлено / пропущено
        //  - помодульно по таблицам.
        RestoreStats stats = new RestoreStats();

        // === 5.1. Обработка таблиц, которые запрошены, но отсутствуют в бэкапе ===.
        // Подсчитываем таблицы, которые есть в списке tables, но нет в filteredBackup.data()
        Map<String, List<Map<String, Object>>> backupData = backupWithDefaults.data();
        int tablesMissingInBackup = 0;
        if (tables != null && !tables.isEmpty()) {
            for (String tableName : tables) {
                if (!backupData.containsKey(tableName)) {
                    tablesMissingInBackup++;
                    log.warn("[TABLE_SKIPPED] Таблица '{}' запрошена, но отсутствует в бэкапе", tableName);
                    stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                }
            }
        }
        log.debug("[TABLE_SUMMARY] Запрошено таблиц: {}, в бэкапе: {}, пропущено (нет в бэкапе): {}",
                tables != null ? tables.size() : 0, backupData.size(), tablesMissingInBackup);

        // Получаем стратегию на основе режима восстановления
        RestoreStrategy strategy = restoreStrategyFactory.getStrategy(mode);

        try {
            strategy.restore(
                    dbCredentials,
                    backupWithDefaults,
                    targetDatabase,
                    policy,
                    stats, backupName
            );
        } catch (Exception e) {
            String errorMessage = "Неожиданная ошибка во время восстановления";
            log.error(errorMessage, e);
            throw new RestoreOperationException(
                    errorMessage,
                    null,
                    null,
                    0,
                    e.getMessage(),
                    e
            );
        }

        // === 7. Фиксация времени окончания операции восстановления ===
        Instant finishedAt = Instant.now();

        // === 8. Формируем сводку по результатам (агрегация статистики) ===
        RestoreSummary summary = new RestoreSummary(
                stats.getTablesTotal(),
                stats.getTablesProcessed(),
                stats.getTablesFailed(),
                stats.getTablesSkipped(),
                stats.getRowsInserted(),
                stats.getRowsUpdated(),
                stats.getRowsSkipped(),
                Map.copyOf(stats.getRowsPerTableInserted()),
                Map.copyOf(stats.getRowsPerTableUpdated()),
                Map.copyOf(stats.getRowsPerTableSkipped())
        );

        // === 9. Определяем общий статус восстановления на основе сводки ===
        RestoreStatus status = getRestoreStatus(summary);

        // === 10. Возвращаем итоговый отчёт о восстановлении ===
        return new RestoreReport(
                startedAt,
                finishedAt,
                targetDatabase,
                mode,
                status,
                summary
        );
    }

    private static RestoreStatus getRestoreStatus(RestoreSummary summary) {
        RestoreStatus status;
        if (summary.tablesFailed() == 0 && summary.tablesSkipped() == 0) {
            // Все таблицы успешно восстановлены
            status = RestoreStatus.SUCCESS;
        } else if (summary.tablesProcessed() > 0) {
            // Есть успешно обработанные таблицы, но были ошибки/пропуски
            status = RestoreStatus.COMPLETED_WITH_WARNINGS;
        } else {
            // Ни одной таблицы не удалось обработать до конца
            status = RestoreStatus.FAILED;
        }
        return status;
    }

    /**
     * Разрешает значения для недостающих колонок.
     * <p>
     * Если клиент не указал missingFields или указал неполный,
     * автоматически определяем недостающие колонки (есть в целевой БД, но нет в бэкапе)
     * и генерируем для них значения по умолчанию на основе типа колонки.
     * </p>
     *
     * @param dbCredentials       учётные данные целевой БД
     * @param targetDatabase      имя целевой базы данных
     * @param backup              бэкап с метаданными схемы
     * @param userMissingFields   значения, указанные пользователем (может быть null)
     * @return мапа {таблица -> {колонка -> значение_по_умолчанию}}
     */
    private Map<String, Map<String, Object>> resolveMissingFields(
            DbCredentials dbCredentials,
            String targetDatabase,
            BackupFile backup,
            Map<String, Map<String, Object>> userMissingFields
    ) {
        SchemaMeta backupSchema = backup.schema();
        List<String> backupTableNames = backupSchema.tables().stream()
                .map(TableMeta::name)
                .toList();

        SchemaMeta currentSchema = schemaCompatibilityService
                .getCurrentSchema(dbCredentials, targetDatabase, backupTableNames);

        Map<String, TableMeta> currentByName = currentSchema.tables().stream()
                .collect(java.util.stream.Collectors.toMap(TableMeta::name, t -> t));

        Map<String, Map<String, Object>> autoDefaults = new java.util.HashMap<>();

        for (TableMeta backupTable : backupSchema.tables()) {
            String tableName = backupTable.name();
            TableMeta currentTable = currentByName.get(tableName);
            if (currentTable == null) {
                continue;
            }

            Map<String, ColumnMeta> backupCols = backupTable.columns().stream()
                    .collect(java.util.stream.Collectors.toMap(ColumnMeta::name, c -> c));
            Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                    .collect(java.util.stream.Collectors.toMap(ColumnMeta::name, c -> c));

            for (Map.Entry<String, ColumnMeta> entry : currentCols.entrySet()) {
                String colName = entry.getKey();
                if (!backupCols.containsKey(colName)) {
                    autoDefaults
                            .computeIfAbsent(tableName, k -> new java.util.HashMap<>())
                            .put(colName, getDefaultValueForType(entry.getValue().type(), entry.getValue().nullable()));
                }
            }
        }

        // === Проверка null-значений в данных бэкапа ===
        // Если колонка есть в бэкапе, но все значения null — добавляем дефолт
        for (Map.Entry<String, List<Map<String, Object>>> tableEntry : backup.data().entrySet()) {
            String tableName = tableEntry.getKey();
            List<Map<String, Object>> rows = tableEntry.getValue();
            if (rows == null || rows.isEmpty()) {
                continue;
            }

            TableMeta currentTable = currentByName.get(tableName);
            if (currentTable == null) {
                continue;
            }

            Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                    .collect(java.util.stream.Collectors.toMap(ColumnMeta::name, c -> c));

            for (Map.Entry<String, ColumnMeta> colEntry : currentCols.entrySet()) {
                String colName = colEntry.getKey();
                ColumnMeta colMeta = colEntry.getValue();

                // Пропускаем, если уже есть дефолт
                Map<String, Object> tableDefaults = autoDefaults.get(tableName);
                if (tableDefaults != null && tableDefaults.containsKey(colName)) {
                    continue;
                }

                // Проверяем, есть ли хотя бы одно null значение для этой колонки
                boolean anyNull = rows.stream().anyMatch(row -> row.get(colName) == null);
                if (anyNull && !colMeta.nullable()) {
                    log.info("[MISSING_FIELDS] Column '{}' in table '{}' has null values, adding default",
                            colName, tableName);
                    autoDefaults
                            .computeIfAbsent(tableName, k -> new java.util.HashMap<>())
                            .put(colName, getDefaultValueForType(colMeta.type(), colMeta.nullable()));
                }
            }
        }

        if (autoDefaults.isEmpty()) {
            return userMissingFields;
        }

        if (userMissingFields == null || userMissingFields.isEmpty()) {
            log.info("[MISSING_FIELDS] Auto-filled defaults for {} tables (client did not provide missingFields)",
                    autoDefaults.size());
            return autoDefaults;
        }

        // Merge: user values override auto-detected ones
        Map<String, Map<String, Object>> merged = new java.util.HashMap<>(autoDefaults);
        for (Map.Entry<String, Map<String, Object>> tableEntry : userMissingFields.entrySet()) {
            String tableName = tableEntry.getKey();
            merged.computeIfAbsent(tableName, k -> new java.util.HashMap<>())
                    .putAll(tableEntry.getValue());
        }

        int overridden = merged.keySet().stream()
                .filter(t -> autoDefaults.containsKey(t) && userMissingFields.containsKey(t))
                .map(t -> {
                    Map<String, Object> auto = autoDefaults.get(t);
                    Map<String, Object> user = userMissingFields.get(t);
                    int count = 0;
                    for (String key : user.keySet()) {
                        if (auto.containsKey(key)) count++;
                    }
                    return count;
                })
                .mapToInt(Integer::intValue)
                .sum();

        if (overridden > 0) {
            log.info("[MISSING_FIELDS] Merged {} auto-detected + user-provided defaults ({} overridden)",
                    autoDefaults.size(), overridden);
        }

        return merged;
    }

    /**
     * Определяет значение по умолчанию на основе типа колонки.
     * Дублирует логику из SchemaCompatibilityService для автономного использования.
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

        // Date/time типы — возвращаем плейсхолдер, который DefaultFunctionResolver разрешит в CURRENT_TIMESTAMP
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
                    return values[0].trim().replaceAll("'", "");
                }
            }
            return "";
        }

        // Строковые типы (varchar, text, char и т.д.)
        return "";
    }

    /**
     * Применяет значения по умолчанию для недостающих колонок к данным бэкапа.
     * <p>
     * Для каждой строки в указанных таблицах добавляет недостающие поля
     * с указанными значениями по умолчанию. Если колонка уже есть в строке —
     * значение не перезаписывается.
     * </p>
     *
     * @param backup        бэкап с данными для восстановления
     * @param missingFields мапа {таблица -> {колонка -> значение_по_умолчанию}}
     * @return новый BackupFile с дополненными данными
     */
    private BackupFile applyMissingFieldsDefaults(
            BackupFile backup,
            Map<String, Map<String, Object>> missingFields
    ) {
        if (missingFields == null || missingFields.isEmpty()) {
            return backup;
        }

        Map<String, List<Map<String, Object>>> newData = new java.util.HashMap<>();
        boolean anyChanges = false;

        for (Map.Entry<String, List<Map<String, Object>>> tableEntry : backup.data().entrySet()) {
            String tableName = tableEntry.getKey();
            List<Map<String, Object>> rows = tableEntry.getValue();
            Map<String, Object> tableDefaults = missingFields.get(tableName);

            if (tableDefaults == null || tableDefaults.isEmpty()) {
                // Нет дефолтов для этой таблицы — копируем как есть
                newData.put(tableName, rows);
            } else {
                // Применяем дефолты к каждой строке
                List<Map<String, Object>> modifiedRows = new java.util.ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Map<String, Object> newRow = new java.util.HashMap<>(row);
                    for (Map.Entry<String, Object> field : tableDefaults.entrySet()) {
                        String columnName = field.getKey();
                        Object defaultValue = field.getValue();
                        // Добавляем/перезаписываем если колонка отсутствует ИЛИ имеет null значение
                        Object existingValue = newRow.get(columnName);
                        if (!newRow.containsKey(columnName) || existingValue == null) {
                            // Разрешаем плейсхолдеры (например, #uuid, #now)
                            Object resolvedValue = functionResolver.resolve(defaultValue);
                            newRow.put(columnName, resolvedValue);
                            anyChanges = true;
                        }
                    }
                    modifiedRows.add(newRow);
                }
                newData.put(tableName, modifiedRows);
            }
        }

        if (!anyChanges) {
            return backup;
        }

        log.debug("[MISSING_FIELDS] Применены дефолты для таблиц: {}", missingFields.keySet());
        return new BackupFile(
                backup.database(),
                backup.engine(),
                backup.schema(),
                newData,
                backup.tableOrder()
        );
    }


}