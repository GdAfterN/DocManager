package com.javaee.docmanager.common.model;

import lombok.Data;
import java.util.List;

@Data
public class PageResult<T> {
    private List<T> records;
    private long total;
    private int pages;
    private int current;
    private int size;

    public static <T> PageResult<T> of(List<T> records, long total, int current, int size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setCurrent(current);
        result.setSize(size);
        result.setPages((int) Math.ceil((double) total / size));
        return result;
    }
}
