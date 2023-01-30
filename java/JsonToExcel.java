import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class JsonToExcelUtil {

    public static final String DATA = "data";
    public static final String HEADERS = "headers";
    public static final String HEADER_RENAME_MAP = "header_rename_map";
    public static final String CELL_STYLE_MAP = "cell_style_map";
    public static final String DEFAULT_CELL_STYLE = "default_cell_style";

    private static final String ARRAY_MARKER = "[]";
    private static final String PLACEHOLDER = "<placeholder>";

    private JsonToExcelUtil() {
    }

    /**
     * Method to convert json file to Excel file
     *
     * @param jsonData JsonObject containing data for all sheets
     *                 - field name will be the title of the sheet
     *                 - data will be the information below the headers
     *                 - head_rename_map will translate any natual header names to given header names
     *                 - cell_style_map will set the cell color to of the columns that begin with the key
     * @return file
     */
    public static byte[] translateJsonToExcel(JsonObject jsonData) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            // iterating over each sheet
            jsonData.forEach(entry -> {
                // create the workbook sheet
                String sheetName = entry.getKey();
                JsonObject sheetValue = (JsonObject) entry.getValue();
                log.info("translating sheet name={}", sheetName);
                Sheet sheet = workbook.createSheet(sheetName);

                JsonArray sheetData = sheetValue.getJsonArray(DATA);

                // creating cell style for header to make it bold
                CellStyle headerStyle = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                headerStyle.setFont(font);

                // creating the header into the sheet
                List<String> headers = sheetValue.getValue(HEADERS) instanceof JsonArray
                        ? sheetValue.getJsonArray(HEADERS).stream().map(Object::toString).collect(Collectors.toList())
                        : new ArrayList<>();
                if (headers.isEmpty()) {
                    sheetData.forEach(jsonNode -> populateHeaderNames((JsonObject) jsonNode, headers, null));
                }
                Row header = sheet.createRow(0);
                int headerIdx = 0;

                for (String headerName : headers) {
                    Cell cell = header.createCell(headerIdx++);
                    cell.setCellValue(headerName);
                    // apply the bold style to headers
                    cell.setCellStyle(headerStyle);
                }

                // populate placeholder values
                sheetData.forEach(rowData -> headers.forEach(headerName -> addPlaceholderValues((JsonObject) rowData, headerName)));

                // iterate over each object and add to cell
                sheetData.forEach(rowData -> {
                    Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                    populateObjectValues((JsonObject) rowData, row, "");
                });

                // remove placeholder values
                sheet.forEach(row -> {
                    row.forEach(cell -> {
                        if (cell != null
                                && CellType.STRING.equals(cell.getCellType())
                                && PLACEHOLDER.equals(cell.getStringCellValue())) {
                            cell.setCellValue("");
                        }
                    });
                });
                
                // rename headers
                JsonObject headerNameMap = sheetValue.getJsonObject(HEADER_RENAME_MAP);
                if (headerNameMap != null) {
                    sheet.getRow(0).forEach(headerCell -> {
                        String headerName = headerCell.getStringCellValue();
                        if (headerNameMap.containsKey(headerName)) {
                            String newHeaderName = headerNameMap.getString(headerName);
                            headerCell.setCellValue(newHeaderName);
                        }
                    });
                }

                // adjust data in column using autoSizeColumn
                for (int i = 0; i < headers.size(); i++) {
                    sheet.autoSizeColumn(i);
                }

                // set cell background
                if (sheetValue.getValue(CELL_STYLE_MAP) instanceof JsonObject) {
                    Map<String, CellStyle> cellStyleMap = new HashMap<>();
                    sheetValue.getJsonObject(CELL_STYLE_MAP).forEach(styleEntry -> {
                        CellStyle cellStyle = workbook.createCellStyle();
                        IndexedColors indexedColors = IndexedColors.valueOf(styleEntry.getValue().toString());
                        cellStyle.setFillForegroundColor(indexedColors.getIndex());
                        cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                        cellStyleMap.put(styleEntry.getKey(), cellStyle);
                    });
                    // set cell
                    sheet.forEach(row -> {
                        if (row.getRowNum() == 0) {
                            return;
                        }
                        for (int i = 0; i < sheet.getRow(0).getLastCellNum(); i++) {
                            if (row.getCell(i) == null) {
                                row.createCell(i).setCellValue("");
                            }
                            Cell cell = row.getCell(i);
                            String headerName = headers.get(cell.getColumnIndex());
                            String firstField = headerName.split("\\.")[0];
                            if (cellStyleMap.containsKey(firstField)) {
                                cell.setCellStyle(cellStyleMap.get(firstField));
                            } else if (cellStyleMap.containsKey("default_cell_style")) {
                                cell.setCellStyle(cellStyleMap.get("default_cell_style"));
                            }
                        }
                    });
                }
            });
            return getByteData(workbook);
        } catch (Exception e) {
            log.error("error translating json data", e);
            throw e;
        }
    }

    private static Row populateObjectValues(JsonObject jsonObject, Row row, String parentPath) {
        jsonObject.stream().filter(entry -> !(entry.getValue() instanceof JsonObject)
                && !(entry.getValue() instanceof JsonArray)).forEach(entry -> {
            String key = entry.getKey();
            Object value = entry.getValue();
            String nextPath = StringUtils.isBlank(parentPath) ? key : String.format("%s.%s", parentPath, key);
            // add primitive value
            addCellValue(value, row, nextPath);
        });
        jsonObject.stream().filter(entry -> entry.getValue() instanceof JsonObject).forEach(entry -> {
            String key = entry.getKey();
            Object value = entry.getValue();
            String nextPath = StringUtils.isBlank(parentPath) ? key : String.format("%s.%s", parentPath, key);
            // navigate child object
            populateObjectValues((JsonObject) value, row, nextPath);
        });
        return jsonObject.stream().filter(entry -> entry.getValue() instanceof JsonArray).map(entry -> {
            String key = entry.getKey();
            Object value = entry.getValue();
            String nextPath = StringUtils.isBlank(parentPath) ? key : String.format("%s.%s", parentPath, key);
            // loop through array
            nextPath += ARRAY_MARKER;
            return populateArrayValues((JsonArray) value, row, nextPath);
        }).max(Comparator.comparing(Row::getRowNum)).orElse(row);
    }

    private static Row populateArrayValues(JsonArray jsonArray, Row row, String parentPath) {
        for (int i = 0; i < jsonArray.size(); i++) {
            Object item = jsonArray.getValue(i);
            if (item instanceof JsonObject) {
                // navigate child object
                row = populateObjectValues((JsonObject) item, row, parentPath);
            } else {
                // add primitive value
                row = addCellValue(item, row, parentPath);
            }
            if (i > 0) {
                // add placeholder parent values
                addArrayParentPlaceholderValues(row, parentPath);
            }
        }
        return row;
    }

    private static Row addCellValue(Object value, Row row, String headerName) {
        Sheet sheet = row.getSheet();
        Row headerRow = sheet.getRow(0);
        for (Cell headerCell : headerRow) {
            if (headerName.equals(headerCell.getStringCellValue())) {
                int column = headerCell.getColumnIndex();
                while (row.getCell(column) != null && headerName.contains(ARRAY_MARKER)) {
                    row = getNextRow(row);
                }
                if (value instanceof Double) {
                    row.createCell(column).setCellValue((Double) value);
                } else if (value instanceof Integer) {
                    row.createCell(column).setCellValue((Integer) value);
                } else if (value instanceof Boolean) {
                    row.createCell(column).setCellValue((Boolean) value);
                } else {
                    row.createCell(column).setCellValue(String.valueOf(value));
                }
            }
        }
        return row;
    }

    private static void addArrayParentPlaceholderValues(Row row, String parentPath) {
        String arrayParentPath = parentPath.contains(".") ? parentPath.substring(0, parentPath.lastIndexOf(".")) : "";
        Row headerRow = row.getSheet().getRow(0);
        headerRow.forEach(header -> {
            String childPath = header.getStringCellValue();
            for (String parentFieldName : arrayParentPath.split("\\.")) {
                parentFieldName = parentFieldName.replace("[", "\\[").replace("]", "\\]");
                childPath = childPath.replaceAll("^" + parentFieldName + "\\.?", "");
            }
            int column = header.getColumnIndex();
            if (!childPath.contains(ARRAY_MARKER) && row.getCell(column) == null) {
                row.createCell(column).setCellValue(PLACEHOLDER);
            }
        });
    }

    private static Row getNextRow(Row row) {
        Sheet sheet = row.getSheet();
        int nextRowNum = row.getRowNum() + 1;
        return sheet.getRow(nextRowNum) == null ? sheet.createRow(nextRowNum) : sheet.getRow(nextRowNum);
    }

    private static void addPlaceholderValues(JsonObject jsonObject, String fieldPath) {
        if (fieldPath.contains(".")) {
            int dividerIndex = fieldPath.indexOf(".");
            String firstField = fieldPath.substring(0, dividerIndex);
            String newFieldPath = fieldPath.substring(dividerIndex + 1);
            if (firstField.endsWith(ARRAY_MARKER)) {
                // add array placeholder
                String arrayField = firstField.replace(ARRAY_MARKER, "");
                if (!(jsonObject.getValue(arrayField) instanceof JsonArray) || jsonObject.getJsonArray(arrayField).isEmpty()) {
                    jsonObject.put(arrayField, new JsonArray().add(new JsonObject()));
                }
                jsonObject.getJsonArray(arrayField).forEach(item -> addPlaceholderValues((JsonObject) item, newFieldPath));
            } else {
                // add object placeholder
                if (!(jsonObject.getValue(firstField) instanceof JsonObject)) {
                    jsonObject.put(firstField, new JsonObject());
                }
                addPlaceholderValues(jsonObject.getJsonObject(firstField), newFieldPath);
            }
        } else if (fieldPath.endsWith(ARRAY_MARKER)) {
            // add primitive array placeholder
            String arrayField = fieldPath.replace(ARRAY_MARKER, "");
            if (!(jsonObject.getValue(arrayField) instanceof JsonArray) || jsonObject.getJsonArray(arrayField).isEmpty()) {
                jsonObject.put(fieldPath, new JsonArray().add(PLACEHOLDER));
            }
        } else {
            // add primitive value placeholder
            if (!jsonObject.containsKey(fieldPath)) {
                jsonObject.put(fieldPath, PLACEHOLDER);
            }
        }
    }

    private static void populateHeaderNames(JsonObject jsonObject, List<String> headers, String prefix) {
        jsonObject.forEach(entry -> {
            String fieldName = entry.getKey();
            String headerName = StringUtils.isBlank(prefix) ? fieldName : String.format("%s.%s", prefix, fieldName);
            Object value = entry.getValue();
            if (value instanceof JsonObject) {
                populateHeaderNames((JsonObject) value, headers, headerName);
            } else if (value instanceof JsonArray) {
                ((JsonArray) value).forEach(item -> {
                    if (item instanceof JsonObject) {
                        populateHeaderNames((JsonObject) item, headers, headerName + ARRAY_MARKER);
                    } else {
                        String finalHeaderName = headerName + ARRAY_MARKER;
                        if (!headers.contains(finalHeaderName)) {
                            headers.add(finalHeaderName);
                        }
                    }
                });
            } else {
                if (!headers.contains(headerName)) {
                    if (headerName.endsWith(FieldNameConstants.GUID) && StringUtils.isBlank(prefix)) {
                        headers.add(0, headerName);
                    } else {
                        headers.add(headerName);
                    }
                }
            }
        });
    }

    private static byte[] getByteData(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // write the workbook into target byte array
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("error writing workbook to byte array", e);
            throw e;
        }
    }
}