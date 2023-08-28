package ru.curs.hurdygurdy;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@EqualsAndHashCode
@ToString
public final class SchemaComponentDescriptor {
    /**
     * Name of the component/class.
     */
    public final String name;
    /**
     * Names of the properties the class has.
     */
    public final Set<String> properties;
    /**
     * Ancestors of the class.
     */
    public final List<SchemaComponentDescriptor> baseSchemas;

    public SchemaComponentDescriptor(String name, Set<String> properties) {
        this.name = name;
        this.properties = properties;
        baseSchemas = new ArrayList<>();
    }

    public void addBaseSchema(SchemaComponentDescriptor baseSchema) {
        baseSchemas.add(baseSchema);
    }

}
