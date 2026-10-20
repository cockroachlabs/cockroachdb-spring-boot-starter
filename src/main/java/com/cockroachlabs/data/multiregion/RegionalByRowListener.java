package com.cockroachlabs.data.multiregion;

import java.lang.reflect.Field;
import com.cockroachlabs.data.annotation.CrdbRegion;
import com.cockroachlabs.data.annotation.RegionalByRow;
import jakarta.persistence.PrePersist;

public class RegionalByRowListener {

    @PrePersist
    public void setRegionBeforePersist(Object entity) {
        Class<?> clazz = entity.getClass();
        RegionalByRow annotation = clazz.getAnnotation(RegionalByRow.class);
        if (annotation == null) return;

        for (Field field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(CrdbRegion.class)) continue;

            try {
                field.setAccessible(true);
                Object currentValue = field.get(entity);
                if (currentValue != null && !currentValue.toString().isBlank()) {
                    return;
                }

                String region = RegionContext.current()
                        .filter(r -> !r.isBlank())
                        .orElse(annotation.defaultRegion());

                if (!region.isBlank()) {
                    field.set(entity, region);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        "Failed to set crdb_region on " + clazz.getSimpleName(), e);
            }
        }
    }
}
