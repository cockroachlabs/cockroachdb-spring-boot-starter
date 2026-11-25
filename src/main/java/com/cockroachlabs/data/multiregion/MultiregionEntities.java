package com.cockroachlabs.data.multiregion;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.cockroachlabs.data.annotation.CrdbRegion;
import com.cockroachlabs.data.annotation.GlobalTable;
import com.cockroachlabs.data.annotation.RegionalByRow;
import com.cockroachlabs.data.multiregion.MultiregionTable.Kind;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

/**
 * Lists every entity in a Hibernate metamodel with its table and multi-region annotation, if
 * any. Annotations are found on the entity class or a superclass, so a subclass shares its
 * parent's. Table and column names come from the metamodel, so they reflect the application's
 * naming strategy and quoting.
 */
final class MultiregionEntities {

	private MultiregionEntities() {
	}

	static List<MultiregionTable> read(SessionFactoryImplementor sessionFactory) {
		List<MultiregionTable> tables = new ArrayList<>();
		sessionFactory.getMappingMetamodel().forEachEntityDescriptor(persister -> tables.add(read(persister)));
		return tables;
	}

	private static MultiregionTable read(EntityPersister persister) {
		Class<?> type = persister.getMappedClass();
		MergedAnnotations annotations = MergedAnnotations.from(type, SearchStrategy.SUPERCLASS);
		boolean global = annotations.isPresent(GlobalTable.class);
		boolean regionalByRow = annotations.isPresent(RegionalByRow.class);
		Kind kind = global && regionalByRow //
                ? Kind.BOTH //
				: global  //
                    ? Kind.GLOBAL //
				    : regionalByRow //
                        ? Kind.REGIONAL_BY_ROW //
				        : Kind.NONE;

		List<String> name = Identifiers.parse(persister.getMappedTableDetails().getTableName());
		String table = name.get(name.size() - 1);
		String schema = name.size() > 1 ? name.get(name.size() - 2) : null;

		String regionColumn = null;
		if (kind == Kind.REGIONAL_BY_ROW) {
			regionColumn = regionColumn(persister, type);
		}
		return new MultiregionTable(className(type), schema, table, kind, regionColumn);
	}

	/**
	 * The entity's fully qualified name, as a developer types it into an IDE to find the class:
	 * the canonical name ({@code com.example.Outer.Inner}), or the binary name for a class that
	 * has none.
	 */
	static String className(Class<?> type) {
		String canonical = type.getCanonicalName();
		return canonical != null ? canonical : type.getName();
	}

	private static @Nullable String regionColumn(EntityPersister persister, Class<?> type) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (field.isAnnotationPresent(CrdbRegion.class)) {
					AttributeMapping attribute = persister.findAttributeMapping(field.getName());
					if (attribute instanceof SelectableMapping selectable) {
						return Identifiers.parse(selectable.getSelectionExpression()).get(0);
					}
					return null;
				}
			}
		}
		return null;
	}
}
