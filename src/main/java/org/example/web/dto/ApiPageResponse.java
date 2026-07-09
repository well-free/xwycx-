package org.example.web.dto;

import java.util.List;

public record ApiPageResponse<T>(long count, List<T> items) {
}
