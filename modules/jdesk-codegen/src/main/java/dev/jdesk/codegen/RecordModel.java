package dev.jdesk.codegen;

import java.util.List;

/**
 * Validated model of one DTO record for TypeScript interface emission.
 *
 * @param tsName TypeScript interface name (the record's simple name)
 * @param qualifiedName canonical Java name, used for collision diagnostics
 * @param fields components in declaration order
 */
record RecordModel(String tsName, String qualifiedName, List<Field> fields) {

    record Field(String name, String tsType) {
    }
}
