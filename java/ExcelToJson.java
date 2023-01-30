import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

@Slf4j
public class ExcelToJson {

    private static final String ARRAY_MARKER = "[]";
    private static final String ITEM = "<item>";
    private static final String PLACEHOLDER = "<placeholder>";

    private ExcelToJson() {
    }

    /**
     * Convert Excel sheet with data to Json
     */
    public static JsonObject excelToJson(byte[] uploadData, String traceId) throws IOException {
        InputStream inputStream = new ByteArrayInputStream(uploadData);
        Workbook workbook = new XSSFWorkbook(inputStream);
        JsonObject data = new JsonObject();
        Iterator<Sheet> sheetIterator = workbook.sheetIterator();
        // Burn meta sheet
        sheetIterator.next();
        // Loop through sheets
        sheetIterator.forEachRemaining(sheet -> {
            JsonArray sheetArray = new JsonArray();
            data.put(sheet.getSheetName(), sheetArray);
            Iterator<Row> rowIterator = sheet.rowIterator();
            Row headers = rowIterator.next();
            // Set placeholder values
            addPlaceholders(sheet);
            // Loop through rows
            Stack<JsonObject> stack = new Stack<>();
            rowIterator.forEachRemaining(row -> {
                if (!isBlank(row.getCell(0))) {
                    // Row represents a new object
                    JsonObject rowObject = new JsonObject();
                    sheetArray.add(rowObject);
                    stack.clear();
                    stack.push(new JsonObject()
                            .put(FieldNameConstants.KEY, "")
                            .put(FieldNameConstants.VALUE, rowObject)
                    );
                }
                // Loop though columns
                for (int column = 0; column < headers.getLastCellNum(); column++) {
                    try {
                        Cell cell = row.getCell(column);
                        // Get header value for this column
                        Cell headerCell = headers.getCell(column);
                        if (isBlank(cell) || isBlank(headerCell)) {
                            continue;
                        }
                        String key = headerCell.getStringCellValue()
                                .replace(String.format("%s.", ARRAY_MARKER),
                                        String.format("%s.%s.", ARRAY_MARKER, ITEM));
                        String[] keyArray = key.split("\\.");
                        String targetParentKey;
                        if (key.endsWith(ARRAY_MARKER)) {
                            targetParentKey = key;
                        } else {
                            targetParentKey = removeLastKeyField(key);
                        }
                        log.debug("cell_value={} target_key={}", getCellValue(cell), targetParentKey);
                        JsonObject parent = stack.peek();
                        String parentKey = parent.getString(FieldNameConstants.KEY);
                        if (parent.getValue(FieldNameConstants.VALUE) instanceof JsonObject
                                && valueIsAlreadyPopulated(parent.getJsonObject(FieldNameConstants.VALUE), getRemainingKey(key, parentKey))) {
                            parent = resetToPreviousArray(stack);
                            parentKey = parent.getString(FieldNameConstants.KEY);
                        }
                        // Add objects on top of stack until the target parent key is reached
                        while (!targetParentKey.equals(parentKey)) {
                            log.debug("current parent={}", parent);
                            if (isChildKey(parentKey, targetParentKey)) {
                                // Create new object on top of stack
                                String remainingKey = getRemainingKey(key, parentKey);
                                String newKey = remainingKey.split("\\.")[0];
                                Object newValue;
                                if (newKey.endsWith(ARRAY_MARKER)) {
                                    String arrayKey = newKey.replace(ARRAY_MARKER, "");
                                    if (parent.getValue(FieldNameConstants.VALUE) instanceof JsonObject
                                            && parent.getJsonObject(FieldNameConstants.VALUE).getValue(arrayKey) instanceof JsonArray) {
                                        newValue = parent.getJsonObject(FieldNameConstants.VALUE).getValue(arrayKey);
                                    } else {
                                        newValue = new JsonArray();
                                    }
                                } else if (parentKey.endsWith(ARRAY_MARKER)
                                        && parent.getValue(FieldNameConstants.VALUE) instanceof JsonArray
                                        && !parent.getJsonArray(FieldNameConstants.VALUE).isEmpty()) {
                                    JsonArray parentArray = parent.getJsonArray(FieldNameConstants.VALUE);
                                    newValue = parentArray.getJsonObject(parentArray.size() - 1);
                                } else if (parent.getValue(FieldNameConstants.VALUE) instanceof JsonObject
                                        && parent.getJsonObject(FieldNameConstants.VALUE).getValue(newKey) != null) {
                                    newValue = parent.getJsonObject(FieldNameConstants.VALUE).getValue(newKey);
                                } else {
                                    newValue = new JsonObject();
                                }
                                if (StringUtils.isNotBlank(parentKey)) {
                                    newKey = String.format("%s.%s", parentKey, newKey);
                                }
                                JsonObject newParent = new JsonObject()
                                        .put(FieldNameConstants.KEY, newKey)
                                        .put(FieldNameConstants.VALUE, newValue);
                                log.debug("navigating to item={}", newParent);

                                Object parentValue = parent.getValue(FieldNameConstants.VALUE);
                                if (parentValue instanceof JsonObject) {
                                    String childKey = newKey.substring(newKey.lastIndexOf(".") + 1).replace(ARRAY_MARKER, "");
                                    ((JsonObject) parentValue).put(childKey, newValue);
                                } else if (parentValue instanceof JsonArray && !((JsonArray) parentValue).contains(newValue)) {
                                    ((JsonArray) parentValue).add(newValue);
                                }

                                parent = stack.push(newParent);
                            } else {
                                // Backtrack in stack
                                log.debug("backtracking");
                                stack.pop();
                                parent = stack.peek();
                            }
                            parentKey = parent.getString(FieldNameConstants.KEY);
                            if (parent.getValue(FieldNameConstants.VALUE) instanceof JsonObject
                                    && valueIsAlreadyPopulated(parent.getJsonObject(FieldNameConstants.VALUE), getRemainingKey(key, parentKey))) {
                                parent = resetToPreviousArray(stack);
                                parentKey = parent.getString(FieldNameConstants.KEY);
                            }
                        }
                        Object parentValue = parent.getValue(FieldNameConstants.VALUE);
                        log.debug("final parent={}", parent);
                        if (parentValue instanceof JsonObject) {
                            addCellValue((JsonObject) parentValue, keyArray[keyArray.length - 1], cell);
                        } else if (parentValue instanceof JsonArray) {
                            addCellValue((JsonArray) parentValue, cell);
                        }
                    } catch (Exception e) {
                        log.error("error processing cell trace_id=" + traceId, e);
                    }
                }
            });
        });
        // Remove all placeholder and empty values
        JsonObject processedData = CommonUtil.removeNullOrEmpty(data, PLACEHOLDER);
        // Combine child object with parents
        combineObjects(processedData);
        // Get first sheet list
        processedData = new JsonObject().put("data", processedData.getJsonArray(workbook.getSheetName(1)));
        // Add meta data
        addRequestData(processedData, workbook.getSheetAt(0));
        // Close files
        workbook.close();
        inputStream.close();
        return processedData;
    }

    /**
     * Returns true if childKey is a child of parentKey
     */
    private static boolean isChildKey(String parentKey, String childKey) {
        String[] parentKeyArray = parentKey.split("\\.");
        String[] targetParentKeyArray = childKey.split("\\.");
        if ("".equals(parentKey)) {
            return true;
        } else if (parentKeyArray.length > targetParentKeyArray.length) {
            return false;
        }
        for (int i = 0; i < parentKeyArray.length; i++) {
            if (!parentKeyArray[i].equals(targetParentKeyArray[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes last field name from a string of fields
     */
    private static String removeLastKeyField(String key) {
        if (key.contains(".")) {
            return key.substring(0, key.lastIndexOf("."));
        }
        return "";
    }

    /**
     * Get fields defined in key that are past parentKey
     */
    private static String getRemainingKey(String key, String parentKey) {
        if (parentKey.length() >= key.length()) {
            return key;
        }
        return key.substring(parentKey.length()).replaceAll("^\\.", "");
    }

    /**
     * Check if object contains given key
     */
    private static boolean valueIsAlreadyPopulated(JsonObject object, String remainingKey) {
        if (StringUtils.isBlank(remainingKey) || remainingKey.contains(ARRAY_MARKER)) {
            return false;
        }
        log.debug("checking if object={} contains key={}", object, remainingKey);
        for (String key : remainingKey.split("\\.")) {
            if (object.getValue(key) instanceof JsonObject) {
                object = object.getJsonObject(key);
            } else {
                return object.containsKey(key);
            }
        }
        return false;
    }

    /**
     * Backtrack to previous array item and add new object
     */
    private static JsonObject resetToPreviousArray(Stack<JsonObject> stack) {
        log.debug("backtracking array item");
        JsonObject parent = stack.peek();
        // backtrack and create new entry in array
        while (!parent.getString(FieldNameConstants.KEY).endsWith(ARRAY_MARKER)) {
            stack.pop();
            parent = stack.peek();
        }
        log.debug("backtracked to object={}", parent);
        JsonObject newValue = new JsonObject();
        parent.getJsonArray(FieldNameConstants.VALUE).add(newValue);
        String parentKey = parent.getString(FieldNameConstants.KEY) + "." + ITEM;
        parent = stack.push(new JsonObject()
                .put(FieldNameConstants.KEY, parentKey)
                .put(FieldNameConstants.VALUE, newValue)
        );
        log.debug("added item={}", parent);
        return parent;
    }

    /**
     * Adds placeholder values to empty cells for each object.
     * Placeholder values will be removed after translation is complete.
     */
    private static void addPlaceholders(Sheet sheet) {
        Iterator<Row> rowIterator = sheet.rowIterator();
        Row headers = rowIterator.next();
        rowIterator.forEachRemaining(row -> {
            for (int i = 0; i < headers.getLastCellNum(); i++) {
                Cell cell = row.getCell(i);
                String key = headers.getCell(i).getStringCellValue();
                if (!isBlank(cell) && !key.endsWith(ARRAY_MARKER)) {
                    for (int j = 0; j < headers.getLastCellNum(); j++) {
                        Cell blankCell = row.getCell(j);
                        String blankCellKey = headers.getCell(j).getStringCellValue();
                        if (isBlank(blankCell) && keysAreInSameObject(key, blankCellKey)) {
                            log.debug("adding placeholder value at row={} col={}", row.getRowNum(), j);
                            row.createCell(j).setCellValue(PLACEHOLDER);
                        }
                    }
                }
            }
        });
    }

    /**
     * Return true if keys represent a field within the same JsonObject
     */
    private static boolean keysAreInSameObject(String key1, String key2) {
        String[] keyArray1 = key1.split("\\" + ARRAY_MARKER);
        String[] keyArray2 = key2.split("\\" + ARRAY_MARKER);
        if (keyArray1.length != keyArray2.length) {
            return false;
        }
        for (int i = 0; i < keyArray1.length - 1; i++) {
            if (!keyArray1[i].equals(keyArray2[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return true is the cell is null, blank, or contains an empty string
     */
    private static boolean isBlank(Cell cell) {
        return cell == null
                || cell.getCellType().equals(CellType.BLANK)
                || (cell.getCellType().equals(CellType.STRING) && cell.getStringCellValue().isEmpty());
    }

    /**
     * Add cell value to JsonObject depending on cell type
     */
    private static void addCellValue(JsonObject jsonObject, String key, Cell cell) {
        Object cellValue = getCellValue(cell);
        if (cellValue != null) {
            jsonObject.put(key, cellValue);
        }
    }

    /**
     * Add cell value to JsonArray depending on cell type
     */
    private static void addCellValue(JsonArray jsonArray, Cell cell) {
        Object cellValue = getCellValue(cell);
        if (cellValue != null) {
            jsonArray.add(cellValue);
        }
    }

    /**
     * Get cell value depending on cell type
     */
    private static Object getCellValue(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return dateToString(cell.getDateCellValue());
                } else {
                    return cell.getNumericCellValue();
                }
            case BOOLEAN:
                return cell.getBooleanCellValue();
            default:
                return null;
        }
    }

    /**
     * Convert Date to string
     */
    private static String dateToString(Date date) {
        return date.toInstant().toString();
    }

    /**
     * Combines sheets that reference each other
     */
    private static void combineObjects(JsonObject data) {
        String parentIdentifierMarker = "_identifier";
        data.getMap().forEach((sheetName, sheetArrayObject) -> {
            JsonArray sheetArray = (JsonArray) sheetArrayObject;
            sheetArray.forEach(childObject -> {
                JsonObject child = (JsonObject) childObject;
                child.getMap().forEach((childKey, childValue) -> {
                    if (childKey.matches(".*" + parentIdentifierMarker)) {
                        String parentArray = childKey.substring(0,
                                childKey.length() - parentIdentifierMarker.length());
                        for (Object parentObject : data.getJsonArray(parentArray)) {
                            JsonObject parent = (JsonObject) parentObject;
                            if (childValue.equals(parent.getString("identifier"))) {
                                if (!parent.containsKey(sheetName)) {
                                    parent.put(sheetName, new JsonArray());
                                }
                                parent.getJsonArray(sheetName).add(child);
                                break;
                            }
                        }
                    }
                });
            });
        });
    }

    /**
     * Add request data to json object from the fist sheet
     */
    private static void addRequestData(JsonObject data, Sheet meta) {
        Row headerRow = meta.getRow(0);
        Row valueRow = meta.getRow(1);
        JsonObject requestObject = new JsonObject();
        String requestId = UUID.randomUUID().toString();
        requestObject.put("request_id", requestId);
        requestObject.put("timestamp", Instant.now().toString());
        data.put("request", requestObject);
        headerRow.forEach(cell -> {
            String key = cell.getStringCellValue();
            Cell value = valueRow.getCell(cell.getColumnIndex());
            if (value != null) {
                addCellValue(requestObject, key, value);
            }
        });
        data.put(QueryConstants.GUID, requestId);
        data.put(QueryConstants.DOCUMENT_TYPE, "template-load");
    }
}