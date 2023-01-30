public static boolean jsonEqual(JsonObject object1, JsonObject object2) {
    if (object1 == null || object2 == null) {
        return object1 == null && object2 == null;
    } else if (object1.equals(object2)) {
        return true;
    } else if (object1.size() != object2.size()) {
        return false;
    }
    return object1.stream().allMatch(entry -> {
        String key = entry.getKey();
        Object value1 = entry.getValue();
        Object value2 = object2.getValue(key);
        if (value1 == null || value2 == null) {
            return value1 == null && value2 == null;
        } else if (value1 instanceof JsonObject && value2 instanceof JsonObject) {
            return jsonEqual((JsonObject) value1, (JsonObject) value2);
        } else if (value1 instanceof JsonArray && value2 instanceof JsonArray) {
            return jsonEqual((JsonArray) value1, (JsonArray) value2);
        }
        return value1.equals(value2);
    });
}

public static boolean jsonEqual(JsonArray array1, JsonArray array2) {
    if (array1 == null || array2 == null) {
        return array1 == null && array2 == null;
    } else if (array1.equals(array2)) {
        return true;
    } else if (array1.size() != array2.size()) {
        return false;
    }
    return Stream.concat(array1.stream(), array2.stream()).distinct().allMatch(item1 -> {
        long count1 = array1.stream()
                .filter(item2 -> jsonEqual(item1, item2))
                .count();
        long count2 = array2.stream()
                .filter(item2 -> jsonEqual(item1, item2))
                .count();
        return count1 == count2;
    });
}

private static boolean jsonEqual(Object object1, Object object2) {
    if (object1 instanceof JsonObject && object2 instanceof JsonObject) {
        return jsonEqual((JsonObject) object1, (JsonObject) object2);
    } else if (object1 instanceof JsonArray && object2 instanceof JsonArray) {
        return jsonEqual((JsonArray) object1, (JsonArray) object2);
    } else {
        return Objects.equals(object1, object2);
    }
}