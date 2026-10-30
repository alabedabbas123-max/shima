#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_kotlin(void);

enum { OK = 0, CANCELLED = 1, TIMEOUT = 2, PARTIAL = 3, FAILED = 4 };
enum { ERROR_NODE = 1, MISSING_NODE = 2, TYPE_REFERENCE = 3, PACKAGE_NODE = 4,
       IMPORT_NODE = 5, DECLARATION = 6, LITERAL_INITIALIZER = 7, STATIC_CALL = 8,
       LOCAL_TYPE_NAME = 9, RESOURCE_REFERENCE = 10, CLASS_STRUCTURE = 11,
       EXTENDS_STRUCTURE = 12, METHOD_STRUCTURE = 13, METHOD_PARAMETERS = 14,
       METHOD_PARAMETER = 15, FIELD_STRUCTURE = 16, LOCAL_STRUCTURE = 17,
       METHOD_CALL = 18, ASSIGNED_CALL = 19, OPAQUE_CLASS_BODY = 20, MEMBER_ACCESS = 21, LOCAL_SCOPE = 22 };
enum { MOD_PRIVATE = 1, MOD_PUBLIC = 2, MOD_STATIC = 4, MOD_ABSTRACT = 8, MOD_IMPLEMENTS = 16, MOD_DECL_ARRAY = 32 };
enum { HEADER = 2, STRIDE = 6, MAX_VISITED_NODES = 250000 };

typedef struct {
    TSParser *parser;
    TSTree *tree;
    _Atomic(uint32_t) revision;
    int language;
} Session;

typedef struct {
    const char *data;
    uint32_t length;
} Input;

typedef struct {
    Session *session;
    uint32_t revision;
    uint64_t deadline_ns;
    int stopped; /* CANCELLED or TIMEOUT */
} ParseControl;

typedef struct {
    jint *values;
    int count;
    int capacity;
    bool truncated;
} Events;

static uint64_t now_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t) ts.tv_sec * UINT64_C(1000000000) + (uint64_t) ts.tv_nsec;
}

static bool should_stop(ParseControl *control) {
    if (atomic_load_explicit(&control->session->revision, memory_order_acquire) != control->revision) {
        control->stopped = CANCELLED;
        return true;
    }
    uint64_t current = now_ns();
    if (current && current >= control->deadline_ns) {
        control->stopped = TIMEOUT;
        return true;
    }
    return false;
}

static bool progress(TSParseState *state) {
    return should_stop((ParseControl *) state->payload);
}

static const char *read_input(void *payload, uint32_t index, TSPoint point, uint32_t *read) {
    (void) point;
    Input *input = (Input *) payload;
    if (index >= input->length) { *read = 0; return NULL; }
    *read = input->length - index;
    return input->data + index;
}

static bool is_type(TSNode node, const char *type) {
    return !ts_node_is_null(node) && strcmp(ts_node_type(node), type) == 0;
}

static void emit(Events *events, int kind, TSNode primary, TSNode secondary, int extra) {
    if (events->count >= events->capacity) { events->truncated = true; return; }
    int i = HEADER + events->count++ * STRIDE;
    events->values[i] = kind;
    events->values[i + 1] = (jint) (ts_node_start_byte(primary) / 2);
    events->values[i + 2] = (jint) (ts_node_end_byte(primary) / 2);
    events->values[i + 3] = ts_node_is_null(secondary) ? -1 : (jint) (ts_node_start_byte(secondary) / 2);
    events->values[i + 4] = ts_node_is_null(secondary) ? -1 : (jint) (ts_node_end_byte(secondary) / 2);
    events->values[i + 5] = extra;
}

static TSNode field(TSNode node, const char *name) {
    return ts_node_child_by_field_name(node, name, (uint32_t) strlen(name));
}

static bool is_literal(TSNode value) {
    const char *t = ts_node_type(value);
    return strstr(t, "literal") != NULL || strcmp(t, "true") == 0 ||
           strcmp(t, "false") == 0 || strcmp(t, "null_literal") == 0 || strcmp(t, "identifier") == 0;
}

static void collect_initializer(Events *events, TSNode node, int language) {
    if (language == 0) {
        TSNode type = field(node, "type");
        if (ts_node_is_null(type)) return;
        uint32_t count = ts_node_named_child_count(node);
        for (uint32_t i = 0; i < count; i++) {
            TSNode child = ts_node_named_child(node, i);
            if (!is_type(child, "variable_declarator")) continue;
            TSNode value = field(child, "value");
            if (!ts_node_is_null(value) && is_literal(value)) emit(events, LITERAL_INITIALIZER, value, type, 0);
        }
    } else {
        TSNode variable = (TSNode) {0};
        TSNode value = (TSNode) {0};
        uint32_t count = ts_node_named_child_count(node);
        for (uint32_t i = 0; i < count; i++) {
            TSNode child = ts_node_named_child(node, i);
            if (is_type(child, "variable_declaration")) variable = child;
            else value = child;
        }
        if (ts_node_is_null(variable) || ts_node_is_null(value) || !is_literal(value)) return;
        TSNode type = (TSNode) {0};
        for (uint32_t i = 0; i < ts_node_named_child_count(variable); i++) {
            TSNode child = ts_node_named_child(variable, i);
            if (is_type(child, "user_type") || is_type(child, "nullable_type")) { type = child; break; }
        }
        if (!ts_node_is_null(type)) emit(events, LITERAL_INITIALIZER, value, type, 0);
    }
}

static void collect_call(Events *events, TSNode node, int language) {
    TSNode object = (TSNode) {0};
    TSNode name = (TSNode) {0};
    if (language == 0) {
        object = field(node, "object");
        name = field(node, "name");
    } else if (ts_node_named_child_count(node) > 0) {
        TSNode navigation = ts_node_named_child(node, 0);
        if (is_type(navigation, "navigation_expression") && ts_node_named_child_count(navigation) == 2) {
            object = ts_node_named_child(navigation, 0);
            name = ts_node_named_child(navigation, 1);
        }
    }
    if (!ts_node_is_null(object) && !ts_node_is_null(name)) emit(events, STATIC_CALL, object, name, 0);
}

/* Small structural event set for conservative workspace inspections. */
static int modifier_flags(TSNode node) {
    int flags = 0;
    uint32_t count = ts_node_named_child_count(node);
    for (uint32_t i = 0; i < count; i++) {
        TSNode modifiers = ts_node_named_child(node, i);
        if (!is_type(modifiers, "modifiers")) continue;
        for (uint32_t j = 0; j < ts_node_child_count(modifiers); j++) {
            const char *type = ts_node_type(ts_node_child(modifiers, j));
            if (strcmp(type, "private") == 0) flags |= MOD_PRIVATE;
            else if (strcmp(type, "public") == 0) flags |= MOD_PUBLIC;
            else if (strcmp(type, "static") == 0) flags |= MOD_STATIC;
            else if (strcmp(type, "abstract") == 0) flags |= MOD_ABSTRACT;
        }
        break;
    }
    return flags;
}

static void collect_structure(Events *events, TSNode node, int language) {
    const char *type = ts_node_type(node);
    TSNode parent = ts_node_parent(node);
    if (language == 0) {
        if (strcmp(type, "class_body") == 0 && !is_type(parent, "class_declaration"))
            emit(events, OPAQUE_CLASS_BODY, node, (TSNode) {0}, 0);
        if (strcmp(type, "class_declaration") == 0) {
            TSNode name = field(node, "name"), body = field(node, "body");
            if (!ts_node_is_null(name) && !ts_node_is_null(body)) {
                int flags = modifier_flags(node);
                if (!ts_node_is_null(field(node, "interfaces"))) flags |= MOD_IMPLEMENTS;
                emit(events, CLASS_STRUCTURE, name, body, flags);
                TSNode super = field(node, "superclass");
                if (!ts_node_is_null(super)) emit(events, EXTENDS_STRUCTURE, super, body, 0);
            }
        } else if (strcmp(type, "method_declaration") == 0 && is_type(parent, "class_body")) {
            TSNode name = field(node, "name"), params = field(node, "parameters"), ret = field(node, "type");
            if (!ts_node_is_null(name) && !ts_node_is_null(params) && !ts_node_is_null(ret)) {
                emit(events, METHOD_STRUCTURE, name, node, modifier_flags(node));
                emit(events, METHOD_PARAMETERS, params, ret, (int) ts_node_named_child_count(params));
            }
        } else if ((strcmp(type, "formal_parameter") == 0 || strcmp(type, "spread_parameter") == 0) && is_type(parent, "formal_parameters")) {
            TSNode parameter_type = field(node, "type"), name = field(node, "name");
            if (!ts_node_is_null(parameter_type) && !ts_node_is_null(name))
                emit(events, METHOD_PARAMETER, parameter_type, node,
                     strcmp(type, "spread_parameter") == 0 || !ts_node_is_null(field(node, "dimensions")));
        } else if (strcmp(type, "variable_declarator") == 0 &&
                   (is_type(parent, "field_declaration") || is_type(parent, "local_variable_declaration"))) {
            TSNode name = field(node, "name"), variable_type = field(parent, "type"), value = field(node, "value");
            if (!ts_node_is_null(name) && !ts_node_is_null(variable_type)) {
                int flags = modifier_flags(parent);
                if (!ts_node_is_null(field(node, "dimensions"))) flags |= MOD_DECL_ARRAY;
                emit(events, is_type(parent, "field_declaration") ? FIELD_STRUCTURE : LOCAL_STRUCTURE,
                     name, variable_type, flags);
                if (is_type(parent, "local_variable_declaration")) {
                    TSNode scope = ts_node_parent(parent);
                    if (is_type(scope, "block")) emit(events, LOCAL_SCOPE, name, scope, 0);
                }
                if (is_type(value, "method_invocation")) emit(events, ASSIGNED_CALL, value, variable_type, 0);
            }
        } else if (strcmp(type, "method_invocation") == 0) {
            TSNode name = field(node, "name"), object = field(node, "object"), args = field(node, "arguments");
            if (!ts_node_is_null(name) && !ts_node_is_null(args)) {
                int flags = (!ts_node_is_null(object) ? 1 : 0) |
                            (is_type(parent, "expression_statement") ? 2 : 0) |
                            ((int) ts_node_named_child_count(args) << 4);
                emit(events, METHOD_CALL, name, node, flags);
            }
        }
        if (strcmp(type, "field_access") == 0) {
            TSNode object = field(node, "object"), name = field(node, "field");
            if (!ts_node_is_null(object) && !ts_node_is_null(name))
                emit(events, MEMBER_ACCESS, name, node, 0);
            if (is_type(object, "field_access")) emit(events, RESOURCE_REFERENCE, node, (TSNode) {0}, 0);
        }
    } else if (strcmp(type, "navigation_expression") == 0 && ts_node_named_child_count(node) == 2 &&
               is_type(ts_node_named_child(node, 0), "navigation_expression")) {
        emit(events, RESOURCE_REFERENCE, node, (TSNode) {0}, 0);
    }
}

static void collect_node(Events *events, TSNode node, TSNode root, int language) {
    const char *type = ts_node_type(node);
    TSNode parent = ts_node_parent(node);
    collect_structure(events, node, language);
    if (ts_node_is_error(node)) {
        /* Avoid reporting nested ERROR nodes twice. */
        if (ts_node_is_null(parent) || !ts_node_is_error(parent)) emit(events, ERROR_NODE, node, (TSNode) {0}, 0);
    } else if (ts_node_is_missing(node)) {
        /* Some Kotlin grammar internal separators are synthetic, not source tokens. */
        if (type[0] != '_') {
            int expected = strlen(type) == 1 ? (unsigned char) type[0] : 0;
            emit(events, MISSING_NODE, node, (TSNode) {0}, expected);
        }
    }
    if ((language == 0 && strcmp(type, "package_declaration") == 0) ||
        (language == 1 && strcmp(type, "package_header") == 0)) {
        emit(events, PACKAGE_NODE, node, (TSNode) {0}, 0);
    } else if ((language == 0 && strcmp(type, "import_declaration") == 0) ||
               (language == 1 && strcmp(type, "import") == 0)) {
        emit(events, IMPORT_NODE, node, (TSNode) {0}, 0);
    }
    if (ts_node_eq(parent, root) &&
        (strcmp(type, "class_declaration") == 0 || strcmp(type, "interface_declaration") == 0 ||
         strcmp(type, "enum_declaration") == 0 || strcmp(type, "record_declaration") == 0 ||
         strcmp(type, "annotation_type_declaration") == 0 || strcmp(type, "object_declaration") == 0)) {
        TSNode name = field(node, "name");
        if (!ts_node_is_null(name)) emit(events, DECLARATION, name, (TSNode) {0}, 0);
    }
    if (strcmp(type, "type_parameter") == 0) {
        /* Annotations (Java) and variance/modifiers (Kotlin) may precede the name. */
        uint32_t count = ts_node_named_child_count(node);
        for (uint32_t i = 0; i < count; i++) {
            TSNode name = ts_node_named_child(node, i);
            if (is_type(name, "type_identifier") || is_type(name, "identifier")) {
                emit(events, LOCAL_TYPE_NAME, name, (TSNode) {0}, 0);
                break;
            }
        }
    } else if (!ts_node_eq(parent, root) &&
        (strcmp(type, "class_declaration") == 0 || strcmp(type, "interface_declaration") == 0 ||
         strcmp(type, "enum_declaration") == 0 || strcmp(type, "object_declaration") == 0)) {
        TSNode name = field(node, "name");
        if (!ts_node_is_null(name)) emit(events, LOCAL_TYPE_NAME, name, (TSNode) {0}, 0);
    }
    if (language == 0 && strcmp(type, "type_identifier") == 0 && !is_type(parent, "scoped_type_identifier") && !is_type(parent, "type_parameter")) {
        emit(events, TYPE_REFERENCE, node, (TSNode) {0}, 0);
    } else if (language == 0 && strcmp(type, "scoped_type_identifier") == 0 && !is_type(parent, "scoped_type_identifier")) {
        emit(events, TYPE_REFERENCE, node, (TSNode) {0}, 0);
    } else if (language == 1 && strcmp(type, "user_type") == 0) {
        /* Plain and qualified names; generic arguments are visited separately. */
        TSNode first = (TSNode) {0}, last = (TSNode) {0};
        uint32_t count = ts_node_named_child_count(node);
        for (uint32_t i = 0; i < count; i++) {
            TSNode child = ts_node_named_child(node, i);
            if (is_type(child, "identifier")) {
                if (ts_node_is_null(first)) first = child;
                last = child;
            }
        }
        if (!ts_node_is_null(first)) {
            if (ts_node_eq(first, last)) emit(events, TYPE_REFERENCE, first, (TSNode) {0}, 0);
            else {
                /* Use the user_type span for qualified names; Java validates the text. */
                emit(events, TYPE_REFERENCE, node, (TSNode) {0}, 0);
            }
        }
    }
    if ((language == 0 && (strcmp(type, "field_declaration") == 0 || strcmp(type, "local_variable_declaration") == 0)) ||
        (language == 1 && strcmp(type, "property_declaration") == 0)) collect_initializer(events, node, language);
    if ((language == 0 && strcmp(type, "method_invocation") == 0) ||
        (language == 1 && strcmp(type, "call_expression") == 0)) collect_call(events, node, language);
}

static jintArray result(JNIEnv *env, int status, Events *events) {
    int count = events ? events->count : 0;
    jintArray array = (*env)->NewIntArray(env, HEADER + count * STRIDE);
    if (!array) return NULL;
    if (!events) {
        jint header[] = { status, 0 };
        (*env)->SetIntArrayRegion(env, array, 0, HEADER, header);
    } else {
        events->values[0] = status;
        events->values[1] = count;
        (*env)->SetIntArrayRegion(env, array, 0, HEADER + count * STRIDE, events->values);
    }
    return array;
}

JNIEXPORT jlong JNICALL
Java_com_shima_analyzer_core_NativeBridge_create(JNIEnv *env, jclass cls, jint language) {
    (void) env; (void) cls;
    if (language != 0 && language != 1) return 0;
    Session *s = (Session *) calloc(1, sizeof(Session));
    if (!s) return 0;
    s->parser = ts_parser_new();
    s->language = language;
    atomic_init(&s->revision, 0);
    if (!s->parser || !ts_parser_set_language(s->parser, language == 0 ? tree_sitter_java() : tree_sitter_kotlin())) {
        if (s->parser) ts_parser_delete(s->parser);
        free(s);
        return 0;
    }
    return (jlong) (intptr_t) s;
}

JNIEXPORT void JNICALL
Java_com_shima_analyzer_core_NativeBridge_setRevision(JNIEnv *env, jclass cls, jlong handle, jint revision) {
    (void) env; (void) cls;
    Session *s = (Session *) (intptr_t) handle;
    if (s) atomic_store_explicit(&s->revision, (uint32_t) revision, memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_shima_analyzer_core_NativeBridge_destroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    Session *s = (Session *) (intptr_t) handle;
    if (!s) return;
    if (s->tree) ts_tree_delete(s->tree);
    ts_parser_delete(s->parser);
    free(s);
}

JNIEXPORT jintArray JNICALL
Java_com_shima_analyzer_core_NativeBridge_parse(JNIEnv *env, jclass cls, jlong handle, jstring text,
    jint revision, jint timeout_ms, jint max_events, jint start, jint old_end, jint new_end,
    jint start_row, jint start_col, jint old_row, jint old_col, jint new_row, jint new_col) {
    (void) cls;
    Session *s = (Session *) (intptr_t) handle;
    if (!s || !text || max_events <= 0 || max_events > 100000 || timeout_ms <= 0) return result(env, FAILED, NULL);
    jsize length = (*env)->GetStringLength(env, text);
    const jchar *chars = (*env)->GetStringChars(env, text, NULL);
    if (!chars) return NULL;
    ParseControl control = {s, (uint32_t) revision, now_ns() + (uint64_t) timeout_ms * UINT64_C(1000000), 0};
    if (should_stop(&control)) {
        (*env)->ReleaseStringChars(env, text, chars);
        return result(env, control.stopped, NULL);
    }
    TSTree *old_copy = NULL;
    if (s->tree && start >= 0) {
        old_copy = ts_tree_copy(s->tree);
        if (!old_copy) {
            (*env)->ReleaseStringChars(env, text, chars);
            return result(env, FAILED, NULL);
        }
        TSInputEdit edit = {
            .start_byte = (uint32_t) start * 2, .old_end_byte = (uint32_t) old_end * 2, .new_end_byte = (uint32_t) new_end * 2,
            .start_point = {(uint32_t) start_row, (uint32_t) start_col * 2},
            .old_end_point = {(uint32_t) old_row, (uint32_t) old_col * 2},
            .new_end_point = {(uint32_t) new_row, (uint32_t) new_col * 2}
        };
        ts_tree_edit(old_copy, &edit);
    }
    const uint16_t endian = 1;
    Input input = {(const char *) chars, (uint32_t) length * 2};
    TSInput source = {&input, read_input, (*(const uint8_t *) &endian) ? TSInputEncodingUTF16LE : TSInputEncodingUTF16BE, NULL};
    TSParseOptions options = {&control, progress};
    TSTree *tree = ts_parser_parse_with_options(s->parser, old_copy, source, options);
    if (old_copy) ts_tree_delete(old_copy);
    (*env)->ReleaseStringChars(env, text, chars);
    if (!tree) {
        ts_parser_reset(s->parser);
        return result(env, control.stopped ? control.stopped : FAILED, NULL);
    }
    Events events = {0};
    events.capacity = max_events;
    events.values = (jint *) calloc((size_t) HEADER + (size_t) max_events * STRIDE, sizeof(jint));
    if (!events.values) { ts_tree_delete(tree); return result(env, FAILED, NULL); }
    TSNode root = ts_tree_root_node(tree);
    TSTreeCursor cursor = ts_tree_cursor_new(root);
    int visited = 0;
    bool done = false;
    while (!done) {
        if ((visited++ & 255) == 0 && should_stop(&control)) break;
        if (visited > MAX_VISITED_NODES) { events.truncated = true; break; }
        TSNode node = ts_tree_cursor_current_node(&cursor);
        collect_node(&events, node, root, s->language);
        if (events.truncated) break;
        if (ts_tree_cursor_goto_first_child(&cursor)) continue;
        while (!ts_tree_cursor_goto_next_sibling(&cursor)) {
            if (!ts_tree_cursor_goto_parent(&cursor)) { done = true; break; }
        }
    }
    ts_tree_cursor_delete(&cursor);
    if (control.stopped || should_stop(&control)) {
        ts_tree_delete(tree);
        free(events.values);
        return result(env, control.stopped, NULL);
    }
    jintArray out = result(env, events.truncated ? PARTIAL : OK, &events);
    free(events.values);
    if (out) {
        if (s->tree) ts_tree_delete(s->tree);
        s->tree = tree;
    } else ts_tree_delete(tree);
    return out;
}
