#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <sched.h>

#define GC_MAGIC 0x47434F424A4C4CULL
#define GC_HEADER 16
#define GC_ALIGN 16
#define GC_MIN_BLOCK 32

#define FLAG_FREE 0x8000000000000000ULL
#define FLAG_MARK 0x4000000000000000ULL
#define SIZE_MASK 0x0000FFFFFFFFFFFFULL

typedef struct {
    uint64_t size_flags;
    uint64_t magic;
} gc_header;

typedef struct gc_frame {
    uint64_t *locals;
    uint64_t *stack;
    int32_t *sp;
    int32_t local_count;
    struct gc_frame *prev;
} gc_frame;

static char *heap_start;
static char *heap_end;
static char *bump;
static gc_header *free_list;
static uintptr_t *mark_stack;
static size_t mark_top;
static size_t mark_cap;
static size_t live_bytes;
static size_t threshold;
static int collecting;
static pthread_mutex_t heap_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t gc_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t frame_lock = PTHREAD_MUTEX_INITIALIZER;
static __thread gc_frame *tls_frames;
static gc_frame **all_frames;
static size_t all_frame_count;
static size_t all_frame_cap;

extern uint64_t **gc_roots;
extern int64_t gc_root_count;

static void gc_fatal(void)
{
    abort();
}

static void mark_reserve(size_t need)
{
    if (mark_top + need <= mark_cap) {
        return;
    }
    size_t cap = mark_cap ? mark_cap * 2 : 4096;
    while (cap < mark_top + need) {
        cap *= 2;
    }
    uintptr_t *grown = (uintptr_t *)realloc(mark_stack, cap * sizeof(uintptr_t));
    if (!grown) {
        gc_fatal();
    }
    mark_stack = grown;
    mark_cap = cap;
}

static gc_header *header_of(uintptr_t p)
{
    if (p < (uintptr_t)heap_start + GC_HEADER) {
        return 0;
    }
    if (p >= (uintptr_t)heap_end) {
        return 0;
    }
    if (p & (GC_ALIGN - 1)) {
        return 0;
    }
    gc_header *h = (gc_header *)(p - GC_HEADER);
    if (h->magic != GC_MAGIC) {
        return 0;
    }
    uint64_t sf = h->size_flags;
    if (sf & FLAG_FREE) {
        return 0;
    }
    uint64_t sz = sf & SIZE_MASK;
    if (sz < GC_MIN_BLOCK) {
        return 0;
    }
    if ((uintptr_t)h + sz > (uintptr_t)heap_end) {
        return 0;
    }
    return h;
}

static void mark_push(uintptr_t p)
{
    gc_header *h = header_of(p);
    if (!h) {
        return;
    }
    if (h->size_flags & FLAG_MARK) {
        return;
    }
    h->size_flags |= FLAG_MARK;
    mark_reserve(1);
    mark_stack[mark_top++] = (uintptr_t)h;
}

static void mark_scan(uintptr_t base, size_t bytes)
{
    uintptr_t cursor = (base + GC_ALIGN - 1) & ~(uintptr_t)(GC_ALIGN - 1);
    uintptr_t limit = base + bytes;
    while (cursor + sizeof(uintptr_t) <= limit) {
        mark_push(*(uintptr_t *)cursor);
        cursor += sizeof(uintptr_t);
    }
}

static void mark_drain(void)
{
    while (mark_top > 0) {
        gc_header *h = (gc_header *)mark_stack[--mark_top];
        uint64_t sz = h->size_flags & SIZE_MASK;
        mark_scan((uintptr_t)h + GC_HEADER, (size_t)(sz - GC_HEADER));
    }
}

typedef struct thread_node {
    gc_frame **head;
    struct thread_node *next;
} thread_node;

static thread_node *threads_list;

static void thread_register(gc_frame **head)
{
    thread_node *n = (thread_node *)malloc(sizeof(thread_node));
    if (!n) {
        gc_fatal();
    }
    n->head = head;
    pthread_mutex_lock(&frame_lock);
    n->next = threads_list;
    threads_list = n;
    pthread_mutex_unlock(&frame_lock);
}

static void thread_unregister(gc_frame **head)
{
    pthread_mutex_lock(&frame_lock);
    thread_node **cur = &threads_list;
    while (*cur) {
        if ((*cur)->head == head) {
            thread_node *dead = *cur;
            *cur = dead->next;
            free(dead);
            break;
        }
        cur = &(*cur)->next;
    }
    pthread_mutex_unlock(&frame_lock);
}

static void mark_roots(void)
{
    pthread_mutex_lock(&frame_lock);
    for (thread_node *n = threads_list; n; n = n->next) {
        for (gc_frame *f = *n->head; f; f = f->prev) {
            if (f->locals && f->local_count > 0) {
                mark_scan((uintptr_t)f->locals, (size_t)f->local_count * sizeof(uint64_t));
            }
            if (f->stack && f->sp) {
                int32_t depth = *f->sp;
                if (depth < 0) {
                    depth = 0;
                }
                if (depth > 0) {
                    mark_scan((uintptr_t)f->stack, (size_t)depth * sizeof(uint64_t));
                }
            }
        }
    }
    pthread_mutex_unlock(&frame_lock);
    if (gc_roots) {
        for (int64_t i = 0; i < gc_root_count; i++) {
            uint64_t *slot = gc_roots[i];
            if (slot) {
                mark_push((uintptr_t)*slot);
            }
        }
    }
    mark_drain();
}

static void sweep(void)
{
    char *cursor = heap_start;
    free_list = 0;
    live_bytes = 0;
    while (cursor < bump) {
        gc_header *h = (gc_header *)cursor;
        uint64_t sf = h->size_flags;
        uint64_t sz = sf & SIZE_MASK;
        if (sf & FLAG_MARK) {
            h->size_flags = sz;
            live_bytes += (size_t)sz;
        } else {
            h->magic = GC_MAGIC;
            h->size_flags = sz | FLAG_FREE;
            ((gc_header **)(cursor + GC_HEADER))[0] = free_list;
            free_list = h;
        }
        cursor += sz;
    }
}

void gc_collect(void)
{
    pthread_mutex_lock(&gc_lock);
    if (collecting) {
        pthread_mutex_unlock(&gc_lock);
        return;
    }
    collecting = 1;
    mark_top = 0;
    mark_roots();
    sweep();
    size_t half = (size_t)(heap_end - heap_start) / 2;
    threshold = live_bytes * 2 + half / 8;
    if (threshold < 4u * 1024u * 1024u) {
        threshold = 4u * 1024u * 1024u;
    }
    collecting = 0;
    pthread_mutex_unlock(&gc_lock);
}

void gc_init(int64_t bytes)
{
    if (heap_start) {
        return;
    }
    size_t want = (size_t)bytes;
    if (want < 8u * 1024u * 1024u) {
        want = 8u * 1024u * 1024u;
    }
    heap_start = (char *)aligned_alloc((size_t)GC_ALIGN, want);
    if (!heap_start) {
        gc_fatal();
    }
    heap_end = heap_start + want;
    bump = heap_start;
    free_list = 0;
    live_bytes = 0;
    threshold = 4u * 1024u * 1024u;
}

static gc_header *take_from_free(size_t need)
{
    gc_header *prev = 0;
    gc_header *cur = free_list;
    while (cur) {
        uint64_t sz = cur->size_flags & SIZE_MASK;
        if (sz >= need) {
            gc_header *next = ((gc_header **)((char *)cur + GC_HEADER))[0];
            if (prev) {
                ((gc_header **)((char *)prev + GC_HEADER))[0] = next;
            } else {
                free_list = next;
            }
            if (sz >= need + GC_MIN_BLOCK) {
                gc_header *rest = (gc_header *)((char *)cur + need);
                uint64_t rest_sz = sz - need;
                rest->size_flags = rest_sz | FLAG_FREE;
                rest->magic = GC_MAGIC;
                ((gc_header **)((char *)rest + GC_HEADER))[0] = free_list;
                free_list = rest;
                cur->size_flags = need;
            } else {
                cur->size_flags = sz;
            }
            cur->magic = GC_MAGIC;
            live_bytes += (size_t)(cur->size_flags & SIZE_MASK);
            return cur;
        }
        prev = cur;
        cur = ((gc_header **)((char *)cur + GC_HEADER))[0];
    }
    return 0;
}

void *gc_alloc(int64_t size)
{
    if (size < 0) {
        return 0;
    }
    pthread_mutex_lock(&heap_lock);
    if (!heap_start) {
        gc_init(256 * 1024 * 1024);
    }
    size_t need = (size_t)size + GC_HEADER;
    need = (need + GC_ALIGN - 1) & ~(size_t)(GC_ALIGN - 1);
    if (need < GC_MIN_BLOCK) {
        need = GC_MIN_BLOCK;
    }

    gc_header *h = take_from_free(need);
    if (!h && (size_t)(heap_end - bump) >= need) {
        h = (gc_header *)bump;
        bump += need;
        h->size_flags = need;
        h->magic = GC_MAGIC;
        live_bytes += need;
    }
    int need_collect = 0;
    if (!h) {
        need_collect = 1;
    } else if (live_bytes > threshold) {
        need_collect = 1;
    }
    pthread_mutex_unlock(&heap_lock);

    if (need_collect) {
        gc_collect();
        pthread_mutex_lock(&heap_lock);
        h = take_from_free(need);
        if (!h && (size_t)(heap_end - bump) >= need) {
            h = (gc_header *)bump;
            bump += need;
            h->size_flags = need;
            h->magic = GC_MAGIC;
            live_bytes += need;
        }
        pthread_mutex_unlock(&heap_lock);
        if (!h) {
            return 0;
        }
    }

    void *payload = (char *)h + GC_HEADER;
    memset(payload, 0, need - GC_HEADER);
    return payload;
}

void *gc_calloc(int64_t count, int64_t size)
{
    if (count < 0 || size < 0) {
        return 0;
    }
    int64_t total = count * size;
    if (count != 0 && total / count != size) {
        return 0;
    }
    return gc_alloc(total);
}

void gc_frame_push(gc_frame *frame, uint64_t *locals, int32_t local_count, uint64_t *stack, int32_t *sp)
{
    frame->locals = locals;
    frame->local_count = local_count;
    frame->stack = stack;
    frame->sp = sp;
    frame->prev = tls_frames;
    tls_frames = frame;
}

void gc_frame_pop(gc_frame *frame)
{
    if (tls_frames == frame) {
        tls_frames = frame->prev;
    }
}

void gc_thread_attach(void)
{
    thread_register(&tls_frames);
}

void gc_thread_detach(void)
{
    thread_unregister(&tls_frames);
    tls_frames = 0;
}

int64_t gc_live_bytes(void)
{
    return (int64_t)live_bytes;
}

int64_t gc_heap_size(void)
{
    return (int64_t)(heap_end - heap_start);
}

typedef struct {
    void *vtable;
    int32_t type_id;
    int32_t pad0;
    int64_t chars;
    int32_t length;
    int32_t pad1;
} aot_string;

int32_t aot_string_type_id;
void *aot_string_vtable;
int64_t aot_object_type_id;
int32_t aot_integer_type_id;
int32_t aot_long_type_id;
int32_t aot_class_type_id;
int32_t aot_str_equals(int64_t a, int64_t b);
int64_t aot_str_substring(int64_t s, int32_t from, int32_t to);
static int64_t *class_cache;
static int32_t class_cache_size;
static int64_t *class_names;
static int32_t class_count;

void aot_set_class_type(int32_t type_id)
{
    aot_class_type_id = type_id;
}

void aot_set_class_table(int64_t *names, int32_t count)
{
    class_names = names;
    class_count = count;
    class_cache_size = count + 1;
    class_cache = (int64_t *)calloc((size_t)class_cache_size, sizeof(int64_t));
}

int64_t aot_class_of_type(int32_t type_id)
{
    if (type_id <= 0 || type_id >= class_cache_size) {
        return 0;
    }
    if (class_cache[type_id] != 0) {
        return class_cache[type_id];
    }
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_class_type_id;
    *(int32_t *)((char *)obj + 12) = type_id;
    *(int64_t *)((char *)obj + 16) = class_names[type_id];
    class_cache[type_id] = (int64_t)(intptr_t)obj;
    return class_cache[type_id];
}

int64_t aot_class_of(int64_t obj)
{
    if (obj == 0) {
        return 0;
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)obj + 8);
    return aot_class_of_type(tid);
}

int32_t aot_class_id(int64_t cls)
{
    if (cls == 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)cls + 12);
}

int64_t aot_class_get_name(int64_t cls)
{
    if (cls == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)cls + 16);
}

int64_t aot_str_simple_name(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    int32_t lastDot = -1;
    int32_t lastDollar = -1;
    for (int32_t i = 0; i < str->length; i++) {
        if (c[i] == '.') {
            lastDot = i;
        } else if (c[i] == '$') {
            lastDollar = i;
        }
    }
    int32_t from = lastDot > lastDollar ? lastDot : lastDollar;
    return aot_str_substring(s, from + 1, str->length);
}

int64_t aot_class_for_name(int64_t name)
{
    if (name == 0 || class_names == 0) {
        return 0;
    }
    for (int32_t i = 1; i < class_cache_size; i++) {
        if (class_names[i] != 0 && aot_str_equals(class_names[i], name)) {
            return aot_class_of_type(i);
        }
    }
    for (int32_t i = 1; i < class_cache_size; i++) {
        if (class_names[i] != 0 && aot_str_equals(aot_str_simple_name(class_names[i]), name)) {
            return aot_class_of_type(i);
        }
    }
    return 0;
}

int32_t aot_field_type_id;
int32_t aot_method_type_id;

void aot_set_field_type(int32_t type_id)
{
    aot_field_type_id = type_id;
}

void aot_set_method_type(int32_t type_id)
{
    aot_method_type_id = type_id;
}

int64_t aot_field_new(int32_t owner_tid, int32_t offset, int32_t kind, int64_t name, int32_t is_static)
{
    void *obj = gc_alloc(40);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_field_type_id;
    *(int32_t *)((char *)obj + 12) = owner_tid;
    *(int32_t *)((char *)obj + 16) = offset;
    *(int32_t *)((char *)obj + 20) = kind;
    *(int32_t *)((char *)obj + 24) = is_static;
    *(int64_t *)((char *)obj + 32) = name;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_method_new(int32_t owner_tid, int32_t slot, int64_t name)
{
    void *obj = gc_alloc(32);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_method_type_id;
    *(int32_t *)((char *)obj + 12) = owner_tid;
    *(int32_t *)((char *)obj + 16) = slot;
    *(int64_t *)((char *)obj + 24) = name;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_field_get(int64_t field, int64_t target)
{
    if (field == 0) {
        return 0;
    }
    int32_t offset = *(int32_t *)((char *)(intptr_t)field + 16);
    int32_t kind = *(int32_t *)((char *)(intptr_t)field + 20);
    int32_t is_static = *(int32_t *)((char *)(intptr_t)field + 24);
    int64_t base = is_static ? 0 : target;
    if (base == 0) {
        return 0;
    }
    char *p = (char *)(intptr_t)base + offset;
    if (kind == 1 || kind == 2 || kind == 3) {
        return (int64_t)*(int32_t *)p;
    }
    if (kind == 4) {
        float f = *(float *)p;
        int32_t bits;
        memcpy(&bits, &f, sizeof(float));
        return (int64_t)bits;
    }
    return *(int64_t *)p;
}

int64_t aot_field_set(int64_t field, int64_t target, int64_t value)
{
    if (field == 0) {
        return 0;
    }
    int32_t offset = *(int32_t *)((char *)(intptr_t)field + 16);
    int32_t kind = *(int32_t *)((char *)(intptr_t)field + 20);
    int32_t is_static = *(int32_t *)((char *)(intptr_t)field + 24);
    int64_t base = is_static ? 0 : target;
    if (base == 0) {
        return 0;
    }
    char *p = (char *)(intptr_t)base + offset;
    if (kind == 1 || kind == 2 || kind == 3) {
        *(int32_t *)p = (int32_t)value;
        return value;
    }
    if (kind == 4) {
        int32_t bits = (int32_t)value;
        float f;
        memcpy(&f, &bits, sizeof(float));
        *(float *)p = f;
        return value;
    }
    *(int64_t *)p = value;
    return value;
}

int64_t aot_long_box_new(int64_t v);
int64_t aot_box_new(int32_t v);

int64_t aot_field_get_boxed(int64_t field, int64_t target)
{
    int64_t raw = aot_field_get(field, target);
    if (field == 0) {
        return 0;
    }
    int32_t kind = *(int32_t *)((char *)(intptr_t)field + 20);
    if (kind == 5) {
        return raw;
    }
    if (kind == 6) {
        return aot_long_box_new(raw);
    }
    return aot_box_new((int32_t)raw);
}

int64_t aot_field_name(int64_t field)
{
    if (field == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)field + 32);
}

int64_t aot_method_name(int64_t method)
{
    if (method == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)method + 24);
}

typedef struct {
    int64_t owner_tid;
    int64_t offset;
    int64_t kind;
    int64_t is_static;
    int64_t name;
} field_meta_rec;

static field_meta_rec *field_meta_table;
static int32_t field_meta_count;

void aot_set_field_meta_table(int64_t *data, int32_t count)
{
    field_meta_table = (field_meta_rec *)(intptr_t)data;
    field_meta_count = count;
}

int64_t aot_field_lookup(int32_t owner_tid, int64_t name)
{
    if (name == 0 || field_meta_table == 0) {
        return 0;
    }
    for (int32_t i = 0; i < field_meta_count; i++) {
        field_meta_rec *m = &field_meta_table[i];
        if ((int32_t)m->owner_tid != owner_tid) {
            continue;
        }
        if (m->name != 0 && aot_str_equals(m->name, name)) {
            return aot_field_new(owner_tid, (int32_t)m->offset, (int32_t)m->kind, m->name, (int32_t)m->is_static);
        }
    }
    return 0;
}

void aot_set_string_type(int32_t type_id)
{
    aot_string_type_id = type_id;
}

static int64_t str_alloc(int32_t len)
{
    if (len < 0) {
        return 0;
    }
    int64_t char_bytes = (int64_t)len * 2;
    void *chars = gc_calloc(char_bytes > 0 ? char_bytes : 2, 1);
    if (!chars) {
        return 0;
    }
    void *obj = gc_alloc(32);
    if (!obj) {
        return 0;
    }
    aot_string *s = (aot_string *)obj;
    s->vtable = aot_string_vtable;
    s->type_id = aot_string_type_id;
    s->pad0 = 0;
    s->chars = (int64_t)(intptr_t)chars;
    s->length = len;
    s->pad1 = 0;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_str_from_utf16(int64_t chars_ptr, int32_t len)
{
    int64_t obj = str_alloc(len);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    const uint16_t *src = (const uint16_t *)(intptr_t)chars_ptr;
    for (int32_t i = 0; i < len; i++) {
        dst[i] = src[i];
    }
    return obj;
}

int32_t aot_str_length(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    return ((aot_string *)(intptr_t)s)->length;
}

int64_t aot_str_from_utf8(int64_t ptr, int32_t len)
{
    if (ptr == 0 || len < 0) {
        return str_alloc(0);
    }
    const unsigned char *src = (const unsigned char *)(intptr_t)ptr;
    int64_t obj = str_alloc(len);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    int32_t out = 0;
    int32_t i = 0;
    while (i < len) {
        unsigned char c = src[i];
        uint32_t cp;
        if (c < 0x80) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            cp = (uint32_t)(c & 0x1F) << 6 | (uint32_t)(src[i + 1] & 0x3F);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            cp = (uint32_t)(c & 0x0F) << 12 | (uint32_t)(src[i + 1] & 0x3F) << 6 | (uint32_t)(src[i + 2] & 0x3F);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
            cp = (uint32_t)(c & 0x07) << 18 | (uint32_t)(src[i + 1] & 0x3F) << 12
                | (uint32_t)(src[i + 2] & 0x3F) << 6 | (uint32_t)(src[i + 3] & 0x3F);
            i += 4;
        } else {
            cp = 0xFFFD;
            i += 1;
        }
        if (cp <= 0xFFFF) {
            if (out < len) {
                dst[out++] = (uint16_t)cp;
            }
        } else {
            cp -= 0x10000;
            if (out < len) {
                dst[out++] = (uint16_t)(0xD800 + (cp >> 10));
            }
            if (out < len) {
                dst[out++] = (uint16_t)(0xDC00 + (cp & 0x3FF));
            }
        }
    }
    s->length = out;
    return obj;
}

int32_t aot_str_char_at(int64_t s, int32_t idx)
{
    if (s == 0) {
        return -1;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    if (idx < 0 || idx >= str->length) {
        return -1;
    }
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    return (int32_t)c[idx];
}

int32_t aot_str_equals(int64_t a, int64_t b)
{
    if (a == b) {
        return 1;
    }
    if (a == 0 || b == 0) {
        return 0;
    }
    aot_string *x = (aot_string *)(intptr_t)a;
    aot_string *y = (aot_string *)(intptr_t)b;
    if (x->length != y->length) {
        return 0;
    }
    const uint16_t *cx = (const uint16_t *)(intptr_t)x->chars;
    const uint16_t *cy = (const uint16_t *)(intptr_t)y->chars;
    for (int32_t i = 0; i < x->length; i++) {
        if (cx[i] != cy[i]) {
            return 0;
        }
    }
    return 1;
}

int32_t aot_str_hash(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    int32_t h = 0;
    for (int32_t i = 0; i < str->length; i++) {
        h = 31 * h + (int32_t)c[i];
    }
    return h;
}

int64_t aot_str_concat2(int64_t a, int64_t b)
{
    aot_string *x = a ? (aot_string *)(intptr_t)a : 0;
    aot_string *y = b ? (aot_string *)(intptr_t)b : 0;
    int32_t lx = x ? x->length : 0;
    int32_t ly = y ? y->length : 0;
    int64_t obj = str_alloc(lx + ly);
    if (obj == 0) {
        return 0;
    }
    aot_string *r = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)r->chars;
    if (x) {
        const uint16_t *cx = (const uint16_t *)(intptr_t)x->chars;
        for (int32_t i = 0; i < lx; i++) {
            dst[i] = cx[i];
        }
    }
    if (y) {
        const uint16_t *cy = (const uint16_t *)(intptr_t)y->chars;
        for (int32_t i = 0; i < ly; i++) {
            dst[lx + i] = cy[i];
        }
    }
    return obj;
}

int64_t aot_str_of_int(int32_t v)
{
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%d", (int)v);
    int64_t obj = str_alloc(n);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    for (int i = 0; i < n; i++) {
        dst[i] = (uint16_t)(unsigned char)buf[i];
    }
    return obj;
}

int64_t aot_str_of_long(int64_t v)
{
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%lld", (long long)v);
    int64_t obj = str_alloc(n);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    for (int i = 0; i < n; i++) {
        dst[i] = (uint16_t)(unsigned char)buf[i];
    }
    return obj;
}

int64_t aot_str_of_double(double v)
{
    char buf[64];
    int n = snprintf(buf, sizeof(buf), "%g", v);
    int64_t obj = str_alloc(n);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    for (int i = 0; i < n; i++) {
        dst[i] = (uint16_t)(unsigned char)buf[i];
    }
    return obj;
}

void aot_put_char(int32_t ch)
{
    uint16_t c = (uint16_t)ch;
    if (c < 0x80) {
        putchar((int)c);
    } else if (c < 0x800) {
        putchar(0xC0 | (c >> 6));
        putchar(0x80 | (c & 0x3F));
    } else {
        putchar(0xE0 | (c >> 12));
        putchar(0x80 | ((c >> 6) & 0x3F));
        putchar(0x80 | (c & 0x3F));
    }
}

void aot_str_print(int64_t s)
{
    if (s == 0) {
        fputs("null", stdout);
        return;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    for (int32_t i = 0; i < str->length; i++) {
        uint16_t ch = c[i];
        if (ch < 0x80) {
            putchar((int)ch);
        } else if (ch < 0x800) {
            putchar(0xC0 | (ch >> 6));
            putchar(0x80 | (ch & 0x3F));
        } else {
            putchar(0xE0 | (ch >> 12));
            putchar(0x80 | ((ch >> 6) & 0x3F));
            putchar(0x80 | (ch & 0x3F));
        }
    }
}

int64_t aot_str_substring(int64_t s, int32_t from, int32_t to)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    if (from < 0) {
        from = 0;
    }
    if (to > str->length) {
        to = str->length;
    }
    if (to < from) {
        to = from;
    }
    int32_t len = to - from;
    int64_t obj = str_alloc(len);
    if (obj == 0) {
        return 0;
    }
    aot_string *r = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)r->chars;
    const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
    for (int32_t i = 0; i < len; i++) {
        dst[i] = src[from + i];
    }
    return obj;
}

int32_t aot_str_index_of(int64_t s, int32_t ch, int32_t from)
{
    if (s == 0) {
        return -1;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    if (from < 0) {
        from = 0;
    }
    for (int32_t i = from; i < str->length; i++) {
        if ((int32_t)c[i] == ch) {
            return i;
        }
    }
    return -1;
}

int32_t aot_str_compare_to(int64_t a, int64_t b)
{
    aot_string *x = a ? (aot_string *)(intptr_t)a : 0;
    aot_string *y = b ? (aot_string *)(intptr_t)b : 0;
    const uint16_t *cx = x ? (const uint16_t *)(intptr_t)x->chars : 0;
    const uint16_t *cy = y ? (const uint16_t *)(intptr_t)y->chars : 0;
    int32_t lx = x ? x->length : 0;
    int32_t ly = y ? y->length : 0;
    int32_t n = lx < ly ? lx : ly;
    for (int32_t i = 0; i < n; i++) {
        int32_t d = (int32_t)cx[i] - (int32_t)cy[i];
        if (d != 0) {
            return d;
        }
    }
    return lx - ly;
}

int64_t aot_object_hash(int64_t obj)
{
    uint64_t v = (uint64_t)obj;
    v ^= v >> 33;
    v *= 0xff51afd7ed558ccdULL;
    v ^= v >> 33;
    return (int64_t)(v & 0x7FFFFFFF);
}

typedef struct {
    void *vtable;
    int32_t type_id;
    int32_t pad0;
    uint16_t *chars;
    int32_t length;
    int32_t capacity;
} aot_builder;

static int64_t g_builder_type_id = -1;
static void *g_builder_vtable;

void aot_set_builder_type(int64_t type_id, int64_t vtable)
{
    g_builder_type_id = type_id;
    g_builder_vtable = (void *)(intptr_t)vtable;
}

static int64_t builder_new(int32_t cap)
{
    if (cap < 8) {
        cap = 8;
    }
    uint16_t *buf = (uint16_t *)gc_calloc((int64_t)cap * 2, 1);
    if (!buf) {
        return 0;
    }
    void *obj = gc_alloc(32);
    if (!obj) {
        return 0;
    }
    aot_builder *b = (aot_builder *)obj;
    b->vtable = g_builder_vtable;
    b->type_id = (int32_t)g_builder_type_id;
    b->pad0 = 0;
    b->chars = buf;
    b->length = 0;
    b->capacity = cap;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_sb_new(void)
{
    return builder_new(16);
}

int64_t aot_sb_new_cap(int32_t cap)
{
    return builder_new(cap);
}

int64_t aot_sb_new_str(int64_t s)
{
    int32_t len = s ? ((aot_string *)(intptr_t)s)->length : 0;
    int64_t obj = builder_new(len + 16);
    if (obj == 0) {
        return 0;
    }
    aot_builder *b = (aot_builder *)(intptr_t)obj;
    if (s) {
        aot_string *str = (aot_string *)(intptr_t)s;
        const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
        for (int32_t i = 0; i < len; i++) {
            b->chars[i] = src[i];
        }
        b->length = len;
    }
    return obj;
}

static void builder_grow(aot_builder *b, int32_t need)
{
    if (b->length + need <= b->capacity) {
        return;
    }
    int32_t cap = b->capacity * 2;
    while (cap < b->length + need) {
        cap *= 2;
    }
    uint16_t *nb = (uint16_t *)gc_calloc((int64_t)cap * 2, 1);
    if (!nb) {
        return;
    }
    for (int32_t i = 0; i < b->length; i++) {
        nb[i] = b->chars[i];
    }
    b->chars = nb;
    b->capacity = cap;
}

int64_t aot_sb_append_str(int64_t sb, int64_t s)
{
    if (sb == 0) {
        return 0;
    }
    aot_builder *b = (aot_builder *)(intptr_t)sb;
    if (s == 0) {
        const char *null_txt = "null";
        int32_t n = 4;
        builder_grow(b, n);
        for (int32_t i = 0; i < n; i++) {
            b->chars[b->length + i] = (uint16_t)null_txt[i];
        }
        b->length += n;
        return sb;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    builder_grow(b, str->length);
    const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
    for (int32_t i = 0; i < str->length; i++) {
        b->chars[b->length + i] = src[i];
    }
    b->length += str->length;
    return sb;
}

static void builder_push_ascii(aot_builder *b, const char *txt, int32_t n)
{
    builder_grow(b, n);
    for (int32_t i = 0; i < n; i++) {
        b->chars[b->length + i] = (uint16_t)(unsigned char)txt[i];
    }
    b->length += n;
}

int64_t aot_sb_append_int(int64_t sb, int32_t v)
{
    if (sb == 0) {
        return 0;
    }
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%d", (int)v);
    builder_push_ascii((aot_builder *)(intptr_t)sb, buf, n);
    return sb;
}

int64_t aot_sb_append_long(int64_t sb, int64_t v)
{
    if (sb == 0) {
        return 0;
    }
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%lld", (long long)v);
    builder_push_ascii((aot_builder *)(intptr_t)sb, buf, n);
    return sb;
}

int64_t aot_sb_append_double(int64_t sb, double v)
{
    if (sb == 0) {
        return 0;
    }
    char buf[64];
    int n = snprintf(buf, sizeof(buf), "%g", v);
    builder_push_ascii((aot_builder *)(intptr_t)sb, buf, n);
    return sb;
}

int64_t aot_sb_append_float(int64_t sb, float v)
{
    return aot_sb_append_double(sb, (double)v);
}

int64_t aot_sb_append_bool(int64_t sb, int32_t v)
{
    if (sb == 0) {
        return 0;
    }
    if (v) {
        builder_push_ascii((aot_builder *)(intptr_t)sb, "true", 4);
    } else {
        builder_push_ascii((aot_builder *)(intptr_t)sb, "false", 5);
    }
    return sb;
}

int64_t aot_sb_append_char(int64_t sb, int32_t c)
{
    if (sb == 0) {
        return 0;
    }
    aot_builder *b = (aot_builder *)(intptr_t)sb;
    builder_grow(b, 1);
    b->chars[b->length++] = (uint16_t)c;
    return sb;
}

int32_t aot_sb_length(int64_t sb)
{
    if (sb == 0) {
        return 0;
    }
    return ((aot_builder *)(intptr_t)sb)->length;
}

int64_t aot_sb_to_string(int64_t sb)
{
    if (sb == 0) {
        return 0;
    }
    aot_builder *b = (aot_builder *)(intptr_t)sb;
    int64_t obj = str_alloc(b->length);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    for (int32_t i = 0; i < b->length; i++) {
        dst[i] = b->chars[i];
    }
    return obj;
}

int64_t aot_sb_reverse(int64_t sb)
{
    if (sb == 0) {
        return 0;
    }
    aot_builder *b = (aot_builder *)(intptr_t)sb;
    int32_t lo = 0;
    int32_t hi = b->length - 1;
    while (lo < hi) {
        uint16_t t = b->chars[lo];
        b->chars[lo] = b->chars[hi];
        b->chars[hi] = t;
        lo++;
        hi--;
    }
    return sb;
}

int64_t aot_str_of_char(int32_t c)
{
    int64_t obj = str_alloc(1);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    ((uint16_t *)(intptr_t)s->chars)[0] = (uint16_t)c;
    return obj;
}

int32_t aot_box_int(int64_t box);
int64_t aot_long_box_get(int64_t box);
int64_t aot_str_from_utf8(int64_t ptr, int32_t len);
int64_t aot_tostring_of(int32_t tid, int64_t self) __attribute__((weak));

int64_t aot_indy_begin(void)
{
    return aot_sb_new();
}

int64_t aot_indy_str(int64_t sb, int64_t s)
{
    return aot_sb_append_str(sb, s);
}

int64_t aot_indy_int(int64_t sb, int32_t v)
{
    return aot_sb_append_int(sb, v);
}

int64_t aot_indy_long(int64_t sb, int64_t v)
{
    return aot_sb_append_long(sb, v);
}

int64_t aot_indy_double(int64_t sb, int64_t raw)
{
    double d;
    memcpy(&d, &raw, sizeof(double));
    return aot_sb_append_double(sb, d);
}

int64_t aot_indy_float(int64_t sb, int32_t raw)
{
    float f;
    memcpy(&f, &raw, sizeof(float));
    return aot_sb_append_float(sb, f);
}

int64_t aot_indy_char(int64_t sb, int32_t c)
{
    return aot_sb_append_char(sb, c);
}

int64_t aot_indy_bool(int64_t sb, int32_t v)
{
    return aot_sb_append_bool(sb, v);
}

int64_t aot_indy_obj(int64_t sb, int64_t o)
{
    if (o == 0) {
        return aot_sb_append_str(sb, 0);
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)o + 8);
    if (tid == aot_string_type_id) {
        return aot_sb_append_str(sb, o);
    }
    if (tid == aot_integer_type_id) {
        return aot_sb_append_int(sb, aot_box_int(o));
    }
    if (tid == aot_long_type_id) {
        return aot_sb_append_long(sb, aot_long_box_get(o));
    }
    int64_t s = aot_tostring_of ? aot_tostring_of(tid, o) : 0;
    if (s != 0) {
        return aot_sb_append_str(sb, s);
    }
    char buf[48];
    int n = snprintf(buf, sizeof(buf), "obj@%llx", (unsigned long long)(uintptr_t)o);
    int64_t tmp = aot_str_from_utf8((int64_t)(intptr_t)buf, n);
    return aot_sb_append_str(sb, tmp);
}

int64_t aot_indy_end(int64_t sb)
{
    int64_t r = aot_sb_to_string(sb);
    return r ? r : aot_str_from_utf8((int64_t)(intptr_t)"", 0);
}

int64_t aot_str_of_bool(int32_t v)
{
    const char *txt = v ? "true" : "false";
    int32_t n = v ? 4 : 5;
    int64_t obj = str_alloc(n);
    if (obj == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)obj;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    for (int32_t i = 0; i < n; i++) {
        dst[i] = (uint16_t)(unsigned char)txt[i];
    }
    return obj;
}

int64_t aot_str_of_object(int64_t obj)
{
    if (obj != 0) {
        return obj;
    }
    const char *txt = "null";
    int64_t out = str_alloc(4);
    if (out == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)out;
    uint16_t *dst = (uint16_t *)(intptr_t)s->chars;
    for (int32_t i = 0; i < 4; i++) {
        dst[i] = (uint16_t)(unsigned char)txt[i];
    }
    return out;
}

int32_t aot_int_parse(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    int32_t i = 0;
    int32_t sign = 1;
    if (str->length > 0 && (c[0] == '-' || c[0] == '+')) {
        if (c[0] == '-') {
            sign = -1;
        }
        i = 1;
    }
    int64_t acc = 0;
    while (i < str->length) {
        uint16_t ch = c[i];
        if (ch < '0' || ch > '9') {
            break;
        }
        acc = acc * 10 + (ch - '0');
        if (acc > 2147483648LL) {
            acc = 2147483648LL;
        }
        i++;
    }
    return (int32_t)(sign * acc);
}

int64_t aot_long_parse(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    int32_t i = 0;
    int64_t sign = 1;
    if (str->length > 0 && (c[0] == '-' || c[0] == '+')) {
        if (c[0] == '-') {
            sign = -1;
        }
        i = 1;
    }
    int64_t acc = 0;
    while (i < str->length) {
        uint16_t ch = c[i];
        if (ch < '0' || ch > '9') {
            break;
        }
        acc = acc * 10 + (ch - '0');
        i++;
    }
    return sign * acc;
}

int64_t aot_str_to_lower(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    int64_t obj = str_alloc(str->length);
    if (obj == 0) {
        return 0;
    }
    aot_string *r = (aot_string *)(intptr_t)obj;
    const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
    uint16_t *dst = (uint16_t *)(intptr_t)r->chars;
    for (int32_t i = 0; i < str->length; i++) {
        uint16_t ch = src[i];
        if (ch >= 'A' && ch <= 'Z') {
            ch = (uint16_t)(ch + 32);
        }
        dst[i] = ch;
    }
    return obj;
}

int64_t aot_str_to_upper(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    int64_t obj = str_alloc(str->length);
    if (obj == 0) {
        return 0;
    }
    aot_string *r = (aot_string *)(intptr_t)obj;
    const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
    uint16_t *dst = (uint16_t *)(intptr_t)r->chars;
    for (int32_t i = 0; i < str->length; i++) {
        uint16_t ch = src[i];
        if (ch >= 'a' && ch <= 'z') {
            ch = (uint16_t)(ch - 32);
        }
        dst[i] = ch;
    }
    return obj;
}

int32_t aot_str_starts_with(int64_t s, int64_t prefix)
{
    if (s == 0 || prefix == 0) {
        return 0;
    }
    aot_string *a = (aot_string *)(intptr_t)s;
    aot_string *p = (aot_string *)(intptr_t)prefix;
    if (p->length > a->length) {
        return 0;
    }
    const uint16_t *ca = (const uint16_t *)(intptr_t)a->chars;
    const uint16_t *cp = (const uint16_t *)(intptr_t)p->chars;
    for (int32_t i = 0; i < p->length; i++) {
        if (ca[i] != cp[i]) {
            return 0;
        }
    }
    return 1;
}

int32_t aot_str_ends_with(int64_t s, int64_t suffix)
{
    if (s == 0 || suffix == 0) {
        return 0;
    }
    aot_string *a = (aot_string *)(intptr_t)s;
    aot_string *p = (aot_string *)(intptr_t)suffix;
    if (p->length > a->length) {
        return 0;
    }
    const uint16_t *ca = (const uint16_t *)(intptr_t)a->chars;
    const uint16_t *cp = (const uint16_t *)(intptr_t)p->chars;
    int32_t off = a->length - p->length;
    for (int32_t i = 0; i < p->length; i++) {
        if (ca[off + i] != cp[i]) {
            return 0;
        }
    }
    return 1;
}

int32_t aot_str_contains(int64_t s, int64_t sub)
{
    if (s == 0 || sub == 0) {
        return 0;
    }
    aot_string *a = (aot_string *)(intptr_t)s;
    aot_string *p = (aot_string *)(intptr_t)sub;
    if (p->length == 0) {
        return 1;
    }
    if (p->length > a->length) {
        return 0;
    }
    const uint16_t *ca = (const uint16_t *)(intptr_t)a->chars;
    const uint16_t *cp = (const uint16_t *)(intptr_t)p->chars;
    for (int32_t i = 0; i <= a->length - p->length; i++) {
        int32_t ok = 1;
        for (int32_t j = 0; j < p->length; j++) {
            if (ca[i + j] != cp[j]) {
                ok = 0;
                break;
            }
        }
        if (ok) {
            return 1;
        }
    }
    return 0;
}

int32_t aot_str_index_of_str(int64_t s, int64_t sub, int32_t from)
{
    if (s == 0 || sub == 0) {
        return -1;
    }
    aot_string *a = (aot_string *)(intptr_t)s;
    aot_string *p = (aot_string *)(intptr_t)sub;
    if (from < 0) {
        from = 0;
    }
    if (p->length == 0) {
        return from <= a->length ? from : a->length;
    }
    if (p->length > a->length) {
        return -1;
    }
    const uint16_t *ca = (const uint16_t *)(intptr_t)a->chars;
    const uint16_t *cp = (const uint16_t *)(intptr_t)p->chars;
    for (int32_t i = from; i <= a->length - p->length; i++) {
        int32_t ok = 1;
        for (int32_t j = 0; j < p->length; j++) {
            if (ca[i + j] != cp[j]) {
                ok = 0;
                break;
            }
        }
        if (ok) {
            return i;
        }
    }
    return -1;
}

int64_t aot_str_replace(int64_t s, int32_t from_ch, int32_t to_ch)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    int64_t obj = str_alloc(str->length);
    if (obj == 0) {
        return 0;
    }
    aot_string *r = (aot_string *)(intptr_t)obj;
    const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
    uint16_t *dst = (uint16_t *)(intptr_t)r->chars;
    for (int32_t i = 0; i < str->length; i++) {
        dst[i] = (int32_t)src[i] == from_ch ? (uint16_t)to_ch : src[i];
    }
    return obj;
}

int64_t aot_str_trim(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    int32_t lo = 0;
    int32_t hi = str->length;
    while (lo < hi && c[lo] <= ' ') {
        lo++;
    }
    while (hi > lo && c[hi - 1] <= ' ') {
        hi--;
    }
    return aot_str_substring(s, lo, hi);
}

int32_t aot_str_last_index_of(int64_t s, int32_t ch)
{
    if (s == 0) {
        return -1;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    for (int32_t i = str->length - 1; i >= 0; i--) {
        if ((int32_t)c[i] == ch) {
            return i;
        }
    }
    return -1;
}

void aot_set_integer_type(int32_t type_id)
{
    aot_integer_type_id = type_id;
}

void aot_set_long_type(int32_t type_id)
{
    aot_long_type_id = type_id;
}

int64_t aot_box_new(int32_t v)
{
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_integer_type_id;
    *(int32_t *)((char *)obj + 12) = 0;
    *(int32_t *)((char *)obj + 16) = v;
    return (int64_t)(intptr_t)obj;
}

int32_t aot_box_int(int64_t box)
{
    if (box == 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)box + 16);
}

int64_t aot_long_box_new(int64_t v)
{
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_long_type_id;
    *(int32_t *)((char *)obj + 12) = 0;
    *(int64_t *)((char *)obj + 16) = v;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_long_box_get(int64_t box)
{
    if (box == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)box + 16);
}

void aot_print_object(int64_t obj)
{
    if (obj == 0) {
        fputs("null", stdout);
        return;
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)obj + 8);
    if (tid == aot_string_type_id) {
        aot_str_print(obj);
        return;
    }
    if (tid == aot_integer_type_id) {
        printf("%d", aot_box_int(obj));
        return;
    }
    if (tid == aot_long_type_id) {
        printf("%lld", (long long)aot_long_box_get(obj));
        return;
    }
    printf("obj@%llx", (unsigned long long)(uintptr_t)obj);
}

typedef struct aot_thread_rec {
    pthread_t handle;
    int64_t body;
    int64_t self;
    int64_t name;
    int32_t started;
    int32_t joined;
    int32_t detached;
    struct aot_thread_rec *next;
} aot_thread_rec;

static aot_thread_rec *thread_recs;
static pthread_mutex_t thread_rec_lock = PTHREAD_MUTEX_INITIALIZER;

extern void aot_thread_body(int64_t body, int64_t self) __attribute__((weak));

typedef struct {
    int64_t body;
    int64_t self;
} thread_start_arg;

static void *thread_trampoline(void *raw)
{
    thread_start_arg *a = (thread_start_arg *)raw;
    int64_t body = a->body;
    int64_t self = a->self;
    free(a);
    gc_thread_attach();
    if (aot_thread_body) {
        aot_thread_body(body, self);
    }
    gc_thread_detach();
    return 0;
}

int64_t aot_thread_rec_new(void)
{
    aot_thread_rec *rec = (aot_thread_rec *)calloc(1, sizeof(aot_thread_rec));
    if (!rec) {
        return 0;
    }
    pthread_mutex_lock(&thread_rec_lock);
    rec->next = thread_recs;
    thread_recs = rec;
    pthread_mutex_unlock(&thread_rec_lock);
    return (int64_t)(intptr_t)rec;
}

void aot_thread_set_body(int64_t thread, int64_t body, int64_t self)
{
    if (thread == 0) {
        return;
    }
    aot_thread_rec *rec = (aot_thread_rec *)(intptr_t)thread;
    rec->body = body;
    rec->self = self;
}

int64_t aot_thread_new(int64_t body, int64_t self)
{
    aot_thread_rec *rec = (aot_thread_rec *)calloc(1, sizeof(aot_thread_rec));
    if (!rec) {
        return 0;
    }
    rec->body = body;
    rec->self = self;
    pthread_mutex_lock(&thread_rec_lock);
    rec->next = thread_recs;
    thread_recs = rec;
    pthread_mutex_unlock(&thread_rec_lock);
    return (int64_t)(intptr_t)rec;
}

void aot_thread_start(int64_t thread)
{
    if (thread == 0) {
        return;
    }
    aot_thread_rec *rec = (aot_thread_rec *)(intptr_t)thread;
    if (rec->started) {
        return;
    }
    thread_start_arg *a = (thread_start_arg *)malloc(sizeof(thread_start_arg));
    if (!a) {
        return;
    }
    a->body = rec->body;
    a->self = rec->self;
    rec->started = 1;
    if (pthread_create(&rec->handle, 0, thread_trampoline, a) != 0) {
        rec->started = 0;
        free(a);
    }
}

void aot_thread_join(int64_t thread)
{
    if (thread == 0) {
        return;
    }
    aot_thread_rec *rec = (aot_thread_rec *)(intptr_t)thread;
    if (!rec->started || rec->joined) {
        return;
    }
    rec->joined = 1;
    pthread_join(rec->handle, 0);
}

void aot_thread_set_name(int64_t thread, int64_t name)
{
    if (thread == 0) {
        return;
    }
    aot_thread_rec *rec = (aot_thread_rec *)(intptr_t)thread;
    rec->name = name;
}

void aot_thread_sleep(int64_t millis)
{
    if (millis <= 0) {
        return;
    }
    struct timespec ts;
    ts.tv_sec = (time_t)(millis / 1000);
    ts.tv_nsec = (long)((millis % 1000) * 1000000L);
    nanosleep(&ts, 0);
}

void aot_thread_yield(void)
{
    sched_yield();
}

typedef struct monitor_entry {
    int64_t obj;
    pthread_mutex_t lock;
    int32_t depth;
    struct monitor_entry *next;
} monitor_entry;

static monitor_entry *monitors;
static pthread_mutex_t monitor_table_lock = PTHREAD_MUTEX_INITIALIZER;

static monitor_entry *monitor_of(int64_t obj, int32_t create)
{
    pthread_mutex_lock(&monitor_table_lock);
    for (monitor_entry *e = monitors; e; e = e->next) {
        if (e->obj == obj) {
            pthread_mutex_unlock(&monitor_table_lock);
            return e;
        }
    }
    if (!create) {
        pthread_mutex_unlock(&monitor_table_lock);
        return 0;
    }
    monitor_entry *e = (monitor_entry *)calloc(1, sizeof(monitor_entry));
    if (!e) {
        pthread_mutex_unlock(&monitor_table_lock);
        return 0;
    }
    e->obj = obj;
    pthread_mutex_init(&e->lock, 0);
    e->next = monitors;
    monitors = e;
    pthread_mutex_unlock(&monitor_table_lock);
    return e;
}

int32_t aot_monitor_enter(int64_t obj)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 1);
    if (!e) {
        return 0;
    }
    pthread_mutex_lock(&e->lock);
    e->depth++;
    return 1;
}

int32_t aot_monitor_exit(int64_t obj)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 0);
    if (!e) {
        return 0;
    }
    e->depth--;
    pthread_mutex_unlock(&e->lock);
    return 1;
}

int64_t aot_monitor_depth(int64_t obj)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 0);
    return e ? (int64_t)e->depth : 0;
}

int64_t aot_system_nano(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

int64_t aot_system_millis(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000L);
}

void aot_system_arraycopy(int64_t src, int32_t src_pos, int64_t dst, int32_t dst_pos, int32_t len, int32_t width)
{
    if (src == 0 || dst == 0 || len <= 0) {
        return;
    }
    char *s = (char *)(intptr_t)src + 24 + (int64_t)src_pos * width;
    char *d = (char *)(intptr_t)dst + 24 + (int64_t)dst_pos * width;
    int64_t n = (int64_t)len * width;
    memmove(d, s, (size_t)n);
}

void aot_system_gc(void)
{
    gc_collect();
}

int64_t aot_list_new(void)
{
    void *obj = gc_alloc(48);
    if (!obj) {
        return 0;
    }
    int64_t *slot = (int64_t *)((char *)obj + 16);
    slot[0] = 0;
    slot[1] = 0;
    slot[2] = 0;
    int32_t *cap = (int32_t *)((char *)obj + 40);
    cap[0] = 0;
    return (int64_t)(intptr_t)obj;
}

static int64_t list_slot(int64_t list, int which)
{
    return *(int64_t *)((char *)(intptr_t)list + 16 + which * 8);
}

static int32_t list_cap(int64_t list)
{
    return *(int32_t *)((char *)(intptr_t)list + 40);
}

static void list_set(int64_t list, int which, int64_t v)
{
    *(int64_t *)((char *)(intptr_t)list + 16 + which * 8) = v;
}

static void list_set_cap(int64_t list, int32_t v)
{
    *(int32_t *)((char *)(intptr_t)list + 40) = v;
}

static int64_t list_ensure(int64_t list, int32_t need)
{
    int32_t cap = list_cap(list);
    if (need <= cap) {
        return list_slot(list, 0);
    }
    int32_t ncap = cap < 4 ? 4 : cap * 2;
    while (ncap < need) {
        ncap *= 2;
    }
    void *buf = gc_calloc((int64_t)ncap * 8, 1);
    if (!buf) {
        return 0;
    }
    int64_t old = list_slot(list, 0);
    if (old != 0) {
        int64_t *os = (int64_t *)(intptr_t)old;
        int64_t *ns = (int64_t *)buf;
        int32_t count = (int32_t)list_slot(list, 1);
        for (int32_t i = 0; i < count; i++) {
            ns[i] = os[i];
        }
    }
    list_set(list, 0, (int64_t)(intptr_t)buf);
    list_set_cap(list, ncap);
    return (int64_t)(intptr_t)buf;
}

int64_t aot_list_add(int64_t list, int64_t v)
{
    if (list == 0) {
        return 0;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    int64_t buf = list_ensure(list, size + 1);
    if (buf == 0) {
        return 0;
    }
    ((int64_t *)(intptr_t)buf)[size] = v;
    list_set(list, 1, size + 1);
    return 1;
}

int64_t aot_list_get(int64_t list, int32_t idx)
{
    if (list == 0) {
        return 0;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    if (idx < 0 || idx >= size) {
        return 0;
    }
    return ((int64_t *)(intptr_t)list_slot(list, 0))[idx];
}

int32_t aot_list_size(int64_t list)
{
    if (list == 0) {
        return 0;
    }
    return (int32_t)list_slot(list, 1);
}

int64_t aot_list_set(int64_t list, int32_t idx, int64_t v)
{
    if (list == 0) {
        return 0;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    if (idx < 0 || idx >= size) {
        return 0;
    }
    ((int64_t *)(intptr_t)list_slot(list, 0))[idx] = v;
    return v;
}

int64_t aot_list_remove(int64_t list, int32_t idx)
{
    if (list == 0) {
        return 0;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    if (idx < 0 || idx >= size) {
        return 0;
    }
    int64_t *buf = (int64_t *)(intptr_t)list_slot(list, 0);
    int64_t old = buf[idx];
    for (int32_t i = idx; i < size - 1; i++) {
        buf[i] = buf[i + 1];
    }
    list_set(list, 1, size - 1);
    return old;
}

int32_t aot_list_is_empty(int64_t list)
{
    return aot_list_size(list) == 0 ? 1 : 0;
}

void aot_list_clear(int64_t list)
{
    if (list == 0) {
        return;
    }
    list_set(list, 1, 0);
}

int32_t aot_list_contains(int64_t list, int64_t v)
{
    if (list == 0) {
        return 0;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    int64_t *buf = (int64_t *)(intptr_t)list_slot(list, 0);
    for (int32_t i = 0; i < size; i++) {
        if (buf[i] == v) {
            return 1;
        }
    }
    return 0;
}

int32_t aot_list_index_of(int64_t list, int64_t v)
{
    if (list == 0) {
        return -1;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    int64_t *buf = (int64_t *)(intptr_t)list_slot(list, 0);
    for (int32_t i = 0; i < size; i++) {
        if (buf[i] == v) {
            return i;
        }
    }
    return -1;
}

int64_t aot_list_to_array(int64_t list)
{
    if (list == 0) {
        return 0;
    }
    int32_t size = (int32_t)list_slot(list, 1);
    int64_t total = (int64_t)size * 8 + 24;
    void *arr = gc_calloc(total, 1);
    if (!arr) {
        return 0;
    }
    *(int32_t *)((char *)arr + 16) = size;
    int64_t *dst = (int64_t *)((char *)arr + 24);
    int64_t *src = (int64_t *)(intptr_t)list_slot(list, 0);
    for (int32_t i = 0; i < size; i++) {
        dst[i] = src[i];
    }
    return (int64_t)(intptr_t)arr;
}

int64_t aot_map_new(void)
{
    void *obj = gc_alloc(56);
    if (!obj) {
        return 0;
    }
    memset((char *)obj + 16, 0, 40);
    return (int64_t)(intptr_t)obj;
}

static int64_t map_field(int64_t map, int which)
{
    return *(int64_t *)((char *)(intptr_t)map + 16 + which * 8);
}

static void map_set_field(int64_t map, int which, int64_t v)
{
    *(int64_t *)((char *)(intptr_t)map + 16 + which * 8) = v;
}

static int32_t map_hash(int64_t key)
{
    uint64_t v = (uint64_t)key;
    v ^= v >> 33;
    v *= 0xff51afd7ed558ccdULL;
    v ^= v >> 33;
    return (int32_t)(v & 0x7FFFFFFF);
}

static int64_t map_lookup(int64_t map, int64_t key)
{
    int64_t keys = map_field(map, 0);
    if (keys == 0) {
        return -1;
    }
    int32_t cap = (int32_t)map_field(map, 3);
    int32_t mask = cap - 1;
    int32_t slot = map_hash(key) & mask;
    int64_t *ks = (int64_t *)(intptr_t)keys;
    for (int32_t probe = 0; probe < cap; probe++) {
        int32_t cur = (slot + probe) & mask;
        if (ks[cur] == 0) {
            return -1;
        }
        if (ks[cur] == key) {
            return cur;
        }
    }
    return -1;
}

static void map_ensure(int64_t map, int32_t need)
{
    int64_t keys = map_field(map, 0);
    int32_t cap = (int32_t)map_field(map, 3);
    if (keys != 0 && (int32_t)map_field(map, 2) + need <= cap * 3 / 4) {
        return;
    }
    int32_t ncap = cap < 16 ? 16 : cap * 2;
    void *nk = gc_calloc((int64_t)ncap * 8, 1);
    void *nv = gc_calloc((int64_t)ncap * 8, 1);
    if (!nk || !nv) {
        return;
    }
    int64_t *oldk = (int64_t *)(intptr_t)keys;
    int64_t *oldv = (int64_t *)(intptr_t)map_field(map, 1);
    int64_t *nks = (int64_t *)nk;
    int64_t *nvs = (int64_t *)nv;
    if (keys != 0) {
        int32_t mask = ncap - 1;
        for (int32_t i = 0; i < cap; i++) {
            int64_t k = oldk[i];
            if (k == 0) {
                continue;
            }
            int32_t slot = map_hash(k) & mask;
            for (int32_t probe = 0; probe < ncap; probe++) {
                int32_t cur = (slot + probe) & mask;
                if (nks[cur] == 0) {
                    nks[cur] = k;
                    nvs[cur] = oldv[i];
                    break;
                }
            }
        }
    }
    map_set_field(map, 0, (int64_t)(intptr_t)nk);
    map_set_field(map, 1, (int64_t)(intptr_t)nv);
    map_set_field(map, 2, map_field(map, 2));
    map_set_field(map, 3, (int64_t)ncap);
}

int64_t aot_map_put(int64_t map, int64_t key, int64_t value)
{
    if (map == 0) {
        return 0;
    }
    map_ensure(map, 1);
    int64_t keys = map_field(map, 0);
    int64_t vals = map_field(map, 1);
    int32_t cap = (int32_t)map_field(map, 3);
    int32_t mask = cap - 1;
    int64_t *ks = (int64_t *)(intptr_t)keys;
    int64_t *vs = (int64_t *)(intptr_t)vals;
    int32_t slot = map_hash(key) & mask;
    for (int32_t probe = 0; probe < cap; probe++) {
        int32_t cur = (slot + probe) & mask;
        if (ks[cur] == key) {
            int64_t old = vs[cur];
            vs[cur] = value;
            return old;
        }
        if (ks[cur] == 0) {
            ks[cur] = key;
            vs[cur] = value;
            map_set_field(map, 2, map_field(map, 2) + 1);
            return 0;
        }
    }
    return 0;
}

int64_t aot_map_get(int64_t map, int64_t key)
{
    if (map == 0) {
        return 0;
    }
    int64_t idx = map_lookup(map, key);
    if (idx < 0) {
        return 0;
    }
    int64_t *vs = (int64_t *)(intptr_t)map_field(map, 1);
    return vs[idx];
}

int64_t aot_map_remove(int64_t map, int64_t key)
{
    if (map == 0) {
        return 0;
    }
    int64_t idx = map_lookup(map, key);
    if (idx < 0) {
        return 0;
    }
    int64_t *ks = (int64_t *)(intptr_t)map_field(map, 0);
    int64_t *vs = (int64_t *)(intptr_t)map_field(map, 1);
    int64_t old = vs[idx];
    ks[idx] = 0x7FFFFFFFFFFFFFFFLL;
    vs[idx] = 0;
    map_set_field(map, 2, map_field(map, 2) - 1);
    return old;
}

int32_t aot_map_size(int64_t map)
{
    if (map == 0) {
        return 0;
    }
    return (int32_t)map_field(map, 2);
}

int32_t aot_map_contains(int64_t map, int64_t key)
{
    if (map == 0) {
        return 0;
    }
    return map_lookup(map, key) >= 0 ? 1 : 0;
}

int64_t aot_map_keys(int64_t map)
{
    int64_t list = aot_list_new();
    if (list == 0 || map == 0) {
        return list;
    }
    int64_t keys = map_field(map, 0);
    if (keys == 0) {
        return list;
    }
    int32_t cap = (int32_t)map_field(map, 3);
    int64_t *ks = (int64_t *)(intptr_t)keys;
    for (int32_t i = 0; i < cap; i++) {
        int64_t k = ks[i];
        if (k != 0 && k != 0x7FFFFFFFFFFFFFFFLL) {
            aot_list_add(list, k);
        }
    }
    return list;
}

int64_t aot_map_values(int64_t map)
{
    int64_t list = aot_list_new();
    if (list == 0 || map == 0) {
        return list;
    }
    int64_t keys = map_field(map, 0);
    if (keys == 0) {
        return list;
    }
    int32_t cap = (int32_t)map_field(map, 3);
    int64_t *ks = (int64_t *)(intptr_t)keys;
    int64_t *vs = (int64_t *)(intptr_t)map_field(map, 1);
    for (int32_t i = 0; i < cap; i++) {
        int64_t k = ks[i];
        if (k != 0 && k != 0x7FFFFFFFFFFFFFFFLL) {
            aot_list_add(list, vs[i]);
        }
    }
    return list;
}

void aot_map_clear(int64_t map)
{
    if (map == 0) {
        return;
    }
    int32_t cap = (int32_t)map_field(map, 3);
    if (cap > 0) {
        int64_t *ks = (int64_t *)(intptr_t)map_field(map, 0);
        int64_t *vs = (int64_t *)(intptr_t)map_field(map, 1);
        for (int32_t i = 0; i < cap; i++) {
            ks[i] = 0;
            vs[i] = 0;
        }
    }
    map_set_field(map, 2, 0);
}

int64_t aot_arrays_sort_int(int64_t arr)
{
    if (arr == 0) {
        return 0;
    }
    int32_t len = *(int32_t *)((char *)(intptr_t)arr + 16);
    int32_t *data = (int32_t *)((char *)(intptr_t)arr + 24);
    for (int32_t i = 1; i < len; i++) {
        int32_t key = data[i];
        int32_t j = i - 1;
        while (j >= 0 && data[j] > key) {
            data[j + 1] = data[j];
            j--;
        }
        data[j + 1] = key;
    }
    return arr;
}

int32_t aot_arrays_equals_int(int64_t a, int64_t b)
{
    if (a == b) {
        return 1;
    }
    if (a == 0 || b == 0) {
        return 0;
    }
    int32_t la = *(int32_t *)((char *)(intptr_t)a + 16);
    int32_t lb = *(int32_t *)((char *)(intptr_t)b + 16);
    if (la != lb) {
        return 0;
    }
    int32_t *da = (int32_t *)((char *)(intptr_t)a + 24);
    int32_t *db = (int32_t *)((char *)(intptr_t)b + 24);
    for (int32_t i = 0; i < la; i++) {
        if (da[i] != db[i]) {
            return 0;
        }
    }
    return 1;
}

int64_t aot_arrays_sort(int64_t arr)
{
    if (arr == 0) {
        return 0;
    }
    int32_t len = *(int32_t *)((char *)(intptr_t)arr + 16);
    int64_t *data = (int64_t *)((char *)(intptr_t)arr + 24);
    for (int32_t i = 1; i < len; i++) {
        int64_t key = data[i];
        int32_t j = i - 1;
        while (j >= 0 && data[j] > key) {
            data[j + 1] = data[j];
            j--;
        }
        data[j + 1] = key;
    }
    return arr;
}

int32_t aot_arrays_equals(int64_t a, int64_t b)
{
    if (a == b) {
        return 1;
    }
    if (a == 0 || b == 0) {
        return 0;
    }
    int32_t la = *(int32_t *)((char *)(intptr_t)a + 16);
    int32_t lb = *(int32_t *)((char *)(intptr_t)b + 16);
    if (la != lb) {
        return 0;
    }
    int64_t *da = (int64_t *)((char *)(intptr_t)a + 24);
    int64_t *db = (int64_t *)((char *)(intptr_t)b + 24);
    for (int32_t i = 0; i < la; i++) {
        if (da[i] != db[i]) {
            return 0;
        }
    }
    return 1;
}

int64_t aot_arrays_copy_of_width(int64_t arr, int32_t newLen, int32_t width)
{
    if (arr == 0) {
        return 0;
    }
    int32_t len = *(int32_t *)((char *)(intptr_t)arr + 16);
    int64_t total = (int64_t)(newLen > 0 ? newLen : 0) * width + 24;
    void *out = gc_calloc(total, 1);
    if (!out) {
        return 0;
    }
    *(int32_t *)((char *)out + 16) = newLen;
    int32_t n = newLen < len ? newLen : len;
    if (n > 0) {
        memcpy((char *)out + 24, (char *)(intptr_t)arr + 24, (size_t)((int64_t)n * width));
    }
    return (int64_t)(intptr_t)out;
}

int64_t aot_arrays_copy_of(int64_t arr, int32_t newLen)
{
    if (arr == 0) {
        return 0;
    }
    int32_t len = *(int32_t *)((char *)(intptr_t)arr + 16);
    int64_t total = (int64_t)(newLen > 0 ? newLen : 0) * 8 + 24;
    void *out = gc_calloc(total, 1);
    if (!out) {
        return 0;
    }
    *(int32_t *)((char *)out + 16) = newLen;
    int64_t *src = (int64_t *)((char *)(intptr_t)arr + 24);
    int64_t *dst = (int64_t *)((char *)out + 24);
    int32_t n = newLen < len ? newLen : len;
    for (int32_t i = 0; i < n; i++) {
        dst[i] = src[i];
    }
    return (int64_t)(intptr_t)out;
}