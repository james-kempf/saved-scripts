function jsonObjectEqual(object1, object2) {
    if (object1 === null || object2 === null) {
        return object1 === null && object2 === null;
    } else if (object1 === object2) {
        return true;
    } else if (Object.keys(object1).length !== Object.keys(object2).length) {
        return false;
    }
    return Object.entries(object1).every(([key, value1]) => {
        const value2 = object2[key];
        return jsonEqual(value1, value2);
    });
}

function jsonArrayEqual(array1, array2) {
    if (array1 === null || array2 === null) {
        return array1 === null && array2 === null;
    } else if (array1 === array2) {
        return true;
    } else if (array1.length !== array2.length) {
        return false;
    }
    return [...new Set([...array1, ...array2])].every(item1 => {
        const count1 = array1.filter(item2 => jsonEqual(item1, item2)).length;
        const count2 = array2.filter(item2 => jsonEqual(item1, item2)).length;
        return count1 === count2;
    });
}

function jsonEqual(object1, object2) {
    if (object1 instanceof Object && object2 instanceof Object) {
        return jsonObjectEqual(object1, object2);
    } else if (Array.isArray(object1) && Array.isArray(object2)) {
        return jsonArrayEqual(object1, object2);
    } else {
        return Object.is(object1, object2);
    }
}