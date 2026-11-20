#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <sched.h>
#include <dlfcn.h>
#include <ffi.h>

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
int32_t aot_char_array_type_id;
int32_t aot_string_array_type_id;
int32_t aot_char_box_type_id;
int32_t aot_bool_box_type_id;
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

int64_t aot_method_new(int32_t owner_tid, int32_t slot, int64_t name, int32_t ret_kind)
{
    void *obj = gc_alloc(40);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_method_type_id;
    *(int32_t *)((char *)obj + 12) = owner_tid;
    *(int32_t *)((char *)obj + 16) = slot;
    *(int32_t *)((char *)obj + 20) = ret_kind;
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
    int64_t slot;
    int64_t name;
    int64_t ret_kind;
} method_meta_rec;

static method_meta_rec *method_meta_table;
static int32_t method_meta_count;

extern int64_t aot_mslot_dispatch(int32_t slot, int64_t self, int64_t args) __attribute__((weak));
int64_t aot_box_new(int32_t v);
int64_t aot_long_box_new(int64_t v);

void aot_set_method_meta_table(int64_t *data, int32_t count)
{
    method_meta_table = (method_meta_rec *)(intptr_t)data;
    method_meta_count = count;
}

int64_t aot_method_lookup(int32_t owner_tid, int64_t name)
{
    if (name == 0 || method_meta_table == 0) {
        return 0;
    }
    for (int32_t i = 0; i < method_meta_count; i++) {
        method_meta_rec *m = &method_meta_table[i];
        if ((int32_t)m->owner_tid != owner_tid) {
            continue;
        }
        if (m->name != 0 && aot_str_equals(m->name, name)) {
            return aot_method_new(owner_tid, (int32_t)m->slot, m->name, (int32_t)m->ret_kind);
        }
    }
    return 0;
}

int64_t aot_object_array_ptr(int64_t arr)
{
    if (arr == 0) {
        return 0;
    }
    return arr + 24;
}

typedef struct {
    int64_t owner_tid;
    int64_t desc;
    int64_t fn;
    int64_t arg_count;
} ctor_meta_rec;

static ctor_meta_rec *ctor_meta_table;
static int32_t ctor_meta_count;
static int32_t aot_ctor_type_id;

void aot_set_ctor_type(int32_t type_id)
{
    aot_ctor_type_id = type_id;
}

void aot_set_ctor_meta_table(int64_t *data, int32_t count)
{
    ctor_meta_table = (ctor_meta_rec *)(intptr_t)data;
    ctor_meta_count = count;
}

int64_t aot_ctor_new(int32_t owner_tid, int64_t desc)
{
    void *obj = gc_alloc(32);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_ctor_type_id;
    *(int32_t *)((char *)obj + 12) = owner_tid;
    *(int64_t *)((char *)obj + 16) = desc;
    *(int64_t *)((char *)obj + 24) = 0;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_ctor_lookup(int32_t owner_tid, int64_t desc)
{
    if (ctor_meta_table == 0) {
        return 0;
    }
    for (int32_t i = 0; i < ctor_meta_count; i++) {
        ctor_meta_rec *m = &ctor_meta_table[i];
        if ((int32_t)m->owner_tid != owner_tid) {
            continue;
        }
        if (m->desc != 0 && aot_str_equals(m->desc, desc)) {
            int64_t c = aot_ctor_new(owner_tid, m->desc);
            if (c != 0) {
                *(int64_t *)((char *)(intptr_t)c + 24) = m->fn;
            }
            return c;
        }
    }
    return 0;
}

int64_t aot_unbox_any(int64_t o)
{
    if (o == 0) {
        return 0;
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)o + 8);
    if (tid == aot_integer_type_id) {
        return *(int32_t *)((char *)(intptr_t)o + 16);
    }
    if (tid == aot_long_type_id) {
        return *(int64_t *)((char *)(intptr_t)o + 16);
    }
    return o;
}

int64_t aot_ctor_new_instance(int64_t ctor, int64_t args)
{
    if (ctor == 0) {
        return 0;
    }
    int64_t fn = *(int64_t *)((char *)(intptr_t)ctor + 24);
    if (fn == 0) {
        return 0;
    }
    if (args != 0) {
        int64_t *data = (int64_t *)(intptr_t)args;
        int32_t n = *(int32_t *)((char *)(intptr_t)args - 8);
        for (int32_t i = 0; i < n; i++) {
            data[i] = aot_unbox_any(data[i]);
        }
    }
    int64_t (*f)(int64_t) = (int64_t (*)(int64_t))(intptr_t)fn;
    return f(args);
}

int64_t aot_ctor_name(int64_t ctor)
{
    if (ctor == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)ctor + 16);
}

int64_t aot_method_invoke(int64_t method, int64_t target, int64_t args)
{
    if (method == 0 || aot_mslot_dispatch == 0) {
        return 0;
    }
    int32_t slot = *(int32_t *)((char *)(intptr_t)method + 16);
    int32_t rk = *(int32_t *)((char *)(intptr_t)method + 20);
    int64_t raw = aot_mslot_dispatch(slot, target, args);
    if (rk == 0) {
        return 0;
    }
    if (rk == 1 || rk == 2 || rk == 3 || rk == 4) {
        return aot_box_new((int32_t)raw);
    }
    if (rk == 6) {
        return aot_long_box_new(raw);
    }
    return raw;
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
static int32_t aot_field_array_type_id;
static int32_t aot_method_array_type_id;

void aot_set_reflect_array_types(int32_t field_tid, int32_t method_tid)
{
    aot_field_array_type_id = field_tid;
    aot_method_array_type_id = method_tid;
}

int64_t aot_class_declared_fields(int32_t owner_tid)
{
    if (field_meta_table == 0) {
        return 0;
    }
    int32_t n = 0;
    for (int32_t i = 0; i < field_meta_count; i++) {
        if ((int32_t)field_meta_table[i].owner_tid == owner_tid) {
            n++;
        }
    }
    void *obj = gc_alloc(24 + (int64_t)n * 8);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = 0;
    *(int32_t *)((char *)obj + 8) = aot_field_array_type_id;
    *(int32_t *)((char *)obj + 16) = n;
    int64_t *data = (int64_t *)((char *)obj + 24);
    int32_t k = 0;
    for (int32_t i = 0; i < field_meta_count; i++) {
        field_meta_rec *m = &field_meta_table[i];
        if ((int32_t)m->owner_tid != owner_tid) {
            continue;
        }
        data[k++] = aot_field_new(owner_tid, (int32_t)m->offset, (int32_t)m->kind, m->name, (int32_t)m->is_static);
    }
    return (int64_t)(intptr_t)obj;
}

int64_t aot_class_declared_methods(int32_t owner_tid)
{
    if (method_meta_table == 0) {
        return 0;
    }
    int32_t n = 0;
    for (int32_t i = 0; i < method_meta_count; i++) {
        if ((int32_t)method_meta_table[i].owner_tid == owner_tid) {
            n++;
        }
    }
    void *obj = gc_alloc(24 + (int64_t)n * 8);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = 0;
    *(int32_t *)((char *)obj + 8) = aot_method_array_type_id;
    *(int32_t *)((char *)obj + 16) = n;
    int64_t *data = (int64_t *)((char *)obj + 24);
    int32_t k = 0;
    for (int32_t i = 0; i < method_meta_count; i++) {
        method_meta_rec *m = &method_meta_table[i];
        if ((int32_t)m->owner_tid != owner_tid) {
            continue;
        }
        data[k++] = aot_method_new(owner_tid, (int32_t)m->slot, m->name, (int32_t)m->ret_kind);
    }
    return (int64_t)(intptr_t)obj;
}

static int64_t *type_desc_table;
static int32_t type_desc_count;

int32_t aot_class_id(int64_t cls);
int64_t aot_str_from_utf8(int64_t chars, int32_t len);
int64_t aot_str_concat2(int64_t a, int64_t b);

void aot_set_type_desc_table(int64_t *data, int32_t count)
{
    type_desc_table = data;
    type_desc_count = count;
}

int64_t aot_ctor_desc_of(int64_t arr)
{
    char buf[512];
    int32_t p = 0;
    buf[p++] = '(';
    if (arr != 0) {
        int32_t n = *(int32_t *)((char *)(intptr_t)arr + 16);
        int64_t *data = (int64_t *)((char *)(intptr_t)arr + 24);
        for (int32_t i = 0; i < n && p < 480; i++) {
            int32_t tid = (int32_t)aot_class_id(data[i]);
            int64_t d = 0;
            if (tid > 0 && tid < type_desc_count) {
                d = type_desc_table[tid];
            }
            if (d == 0) {
                const char *fb = "Ljava/lang/Object;";
                for (const char *q = fb; *q != 0 && p < 490; q++) {
                    buf[p++] = *q;
                }
                continue;
            }
            aot_string *s = (aot_string *)(intptr_t)d;
            const uint16_t *cs = (const uint16_t *)(intptr_t)s->chars;
            for (int32_t k = 0; k < s->length && p < 490; k++) {
                buf[p++] = (char)cs[k];
            }
        }
    }
    buf[p++] = ')';
    buf[p++] = 'V';
    return aot_str_from_utf8((int64_t)(intptr_t)buf, p);
}

void aot_set_char_array_type(int32_t type_id)
{
    aot_char_array_type_id = type_id;
}

void aot_set_string_array_type(int32_t type_id)
{
    aot_string_array_type_id = type_id;
}

void aot_set_char_box_type(int32_t type_id)
{
    aot_char_box_type_id = type_id;
}

void aot_set_bool_box_type(int32_t type_id)
{
    aot_bool_box_type_id = type_id;
}

static int32_t *super_table;
static int32_t super_table_size;
static int32_t *iface_table;
static int32_t iface_table_size;
static int8_t *assign_table;
static int32_t assign_table_size;

void aot_set_assign_table(int8_t *data, int32_t size)
{
    assign_table = data;
    assign_table_size = size;
}

int32_t aot_is_subtype(int32_t sub, int32_t sup)
{
    if (sub <= 0 || sup <= 0 || assign_table == 0) {
        return 0;
    }
    if (sub >= assign_table_size || sup >= assign_table_size) {
        return 0;
    }
    return assign_table[(int32_t)sub * assign_table_size + sup] != 0;
}

void aot_set_super_table(int32_t *data, int32_t size)
{
    super_table = data;
    super_table_size = size;
}

void aot_set_iface_table(int32_t *data, int32_t size)
{
    iface_table = data;
    iface_table_size = size;
}

int32_t aot_class_super_tid(int32_t tid)
{
    if (tid <= 0 || tid >= super_table_size || super_table == 0) {
        return 0;
    }
    return super_table[tid];
}

int32_t aot_class_is_interface(int32_t tid)
{
    if (tid <= 0 || tid >= iface_table_size || iface_table == 0) {
        return 0;
    }
    return iface_table[tid];
}

int64_t aot_class_get_superclass(int32_t tid)
{
    int32_t sup = aot_class_super_tid(tid);
    if (sup <= 0) {
        return 0;
    }
    return aot_class_of_type(sup);
}

int32_t aot_class_is_assignable_from(int32_t self_tid, int32_t other_tid)
{
    if (self_tid <= 0 || other_tid <= 0) {
        return 0;
    }
    if (self_tid == other_tid) {
        return 1;
    }
    return aot_is_subtype(other_tid, self_tid);
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

int32_t aot_str_region_equals(int64_t a, int32_t afrom, int64_t b, int32_t bfrom, int32_t len)
{
    if (a == 0 || b == 0) {
        return a == b ? 1 : 0;
    }
    aot_string *x = (aot_string *)(intptr_t)a;
    aot_string *y = (aot_string *)(intptr_t)b;
    if (afrom < 0 || bfrom < 0 || len < 0) {
        return 0;
    }
    if (afrom + len > x->length || bfrom + len > y->length) {
        return 0;
    }
    const uint16_t *cx = (const uint16_t *)(intptr_t)x->chars;
    const uint16_t *cy = (const uint16_t *)(intptr_t)y->chars;
    for (int32_t i = 0; i < len; i++) {
        if (cx[afrom + i] != cy[bfrom + i]) {
            return 0;
        }
    }
    return 1;
}

int64_t aot_str_to_char_array(int64_t s)
{
    int32_t n = aot_str_length(s);
    void *arr = gc_alloc(24 + (int64_t)n * 8);
    if (!arr) {
        return 0;
    }
    *(void **)(char *)arr = 0;
    *(int32_t *)((char *)arr + 8) = aot_char_array_type_id;
    *(int32_t *)((char *)arr + 16) = n;
    int64_t *data = (int64_t *)((char *)arr + 24);
    if (s != 0) {
        aot_string *str = (aot_string *)(intptr_t)s;
        const uint16_t *cs = (const uint16_t *)(intptr_t)str->chars;
        for (int32_t i = 0; i < n; i++) {
            data[i] = (int64_t)cs[i];
        }
    }
    return (int64_t)(intptr_t)arr;
}

int64_t aot_str_repeat(int64_t s, int32_t times)
{
    if (times <= 0 || s == 0) {
        return str_alloc(0);
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    int64_t total = (int64_t)str->length * times;
    if (total > 0x7FFFFFFF) {
        return 0;
    }
    int64_t out = str_alloc((int32_t)total);
    if (out == 0) {
        return 0;
    }
    aot_string *o = (aot_string *)(intptr_t)out;
    const uint16_t *src = (const uint16_t *)(intptr_t)str->chars;
    uint16_t *dst = (uint16_t *)(intptr_t)o->chars;
    int32_t p = 0;
    for (int32_t t = 0; t < times; t++) {
        for (int32_t i = 0; i < str->length; i++) {
            dst[p++] = src[i];
        }
    }
    return out;
}

static int32_t str_is_space(uint16_t c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C;
}

int64_t aot_str_strip(int64_t s)
{
    int32_t n = aot_str_length(s);
    if (n == 0) {
        return str_alloc(0);
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *cs = (const uint16_t *)(intptr_t)str->chars;
    int32_t lo = 0;
    int32_t hi = n;
    while (lo < hi && str_is_space(cs[lo])) {
        lo++;
    }
    while (hi > lo && str_is_space(cs[hi - 1])) {
        hi--;
    }
    if (lo == 0 && hi == n) {
        return s;
    }
    return aot_str_substring(s, lo, hi);
}

int64_t aot_str_split(int64_t s, int64_t sep)
{
    int32_t n = aot_str_length(s);
    int32_t sl = aot_str_length(sep);
    if (sl == 0) {
        void *arr = gc_alloc(24 + (int64_t)n * 8);
        if (!arr) {
            return 0;
        }
        *(void **)(char *)arr = 0;
        *(int32_t *)((char *)arr + 8) = aot_string_array_type_id;
        *(int32_t *)((char *)arr + 16) = n;
        int64_t *data = (int64_t *)((char *)arr + 24);
        aot_string *str = (aot_string *)(intptr_t)s;
        const uint16_t *cs = (const uint16_t *)(intptr_t)str->chars;
        for (int32_t i = 0; i < n; i++) {
            int64_t one = str_alloc(1);
            ((uint16_t *)(intptr_t)((aot_string *)(intptr_t)one)->chars)[0] = cs[i];
            data[i] = one;
        }
        return (int64_t)(intptr_t)arr;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    aot_string *sp2 = (aot_string *)(intptr_t)sep;
    const uint16_t *cs = (const uint16_t *)(intptr_t)str->chars;
    const uint16_t *sp = (const uint16_t *)(intptr_t)sp2->chars;
    int32_t count = 1;
    int32_t i = 0;
    while (i + sl <= n) {
        int32_t hit = 1;
        for (int32_t j = 0; j < sl; j++) {
            if (cs[i + j] != sp[j]) {
                hit = 0;
                break;
            }
        }
        if (hit) {
            count++;
            i += sl;
        } else {
            i++;
        }
    }
    void *arr = gc_alloc(24 + (int64_t)count * 8);
    if (!arr) {
        return 0;
    }
    *(void **)(char *)arr = 0;
    *(int32_t *)((char *)arr + 8) = aot_string_array_type_id;
    *(int32_t *)((char *)arr + 16) = count;
    int64_t *data = (int64_t *)((char *)arr + 24);
    int32_t k = 0;
    int32_t start = 0;
    i = 0;
    while (i + sl <= n) {
        int32_t hit = 1;
        for (int32_t j = 0; j < sl; j++) {
            if (cs[i + j] != sp[j]) {
                hit = 0;
                break;
            }
        }
        if (hit) {
            data[k++] = aot_str_substring(s, start, i);
            i += sl;
            start = i;
        } else {
            i++;
        }
    }
    data[k++] = aot_str_substring(s, start, n);
    while (k > 0 && aot_str_length(data[k - 1]) == 0) {
        k--;
    }
    *(int32_t *)((char *)arr + 16) = k;
    return (int64_t)(intptr_t)arr;
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

extern int64_t aot_mslot_dispatch(int32_t slot, int64_t self, int64_t args) __attribute__((weak));

static int64_t aot_om_getter_i(int32_t off, int64_t self)
{
    return (int64_t)(*(int32_t *)((char *)(intptr_t)self + off));
}

static int64_t aot_om_getter_j(int32_t off, int64_t self)
{
    return *(int64_t *)((char *)(intptr_t)self + off);
}

int32_t aot_om_field_eq_ref(int32_t slot, int64_t a, int64_t b)
{
    int64_t va = aot_om_getter_j(slot, a);
    int64_t vb = aot_om_getter_j(slot, b);
    if (va == vb) {
        return 1;
    }
    if (va == 0 || vb == 0) {
        return 0;
    }
    int32_t ta = *(int32_t *)((char *)(intptr_t)va + 8);
    int32_t tb = *(int32_t *)((char *)(intptr_t)vb + 8);
    if (ta != tb) {
        return 0;
    }
    if (ta == aot_string_type_id) {
        return aot_str_equals(va, vb);
    }
    return 0;
}

int32_t aot_om_field_eq_int(int32_t slot, int64_t a, int64_t b)
{
    return (int32_t)aot_om_getter_i(slot, a) == (int32_t)aot_om_getter_i(slot, b);
}

int32_t aot_om_field_eq_long(int32_t slot, int64_t a, int64_t b)
{
    return aot_om_getter_j(slot, a) == aot_om_getter_j(slot, b);
}

int32_t aot_om_field_eq_double(int32_t slot, int64_t a, int64_t b)
{
    int64_t ra = aot_om_getter_j(slot, a);
    int64_t rb = aot_om_getter_j(slot, b);
    if (ra == rb) {
        return 1;
    }
    double da = 0;
    double db = 0;
    memcpy(&da, &ra, sizeof(double));
    memcpy(&db, &rb, sizeof(double));
    return da == db;
}

int32_t aot_om_hash_int(int32_t slot, int64_t self)
{
    return (int32_t)aot_om_getter_i(slot, self);
}

int32_t aot_om_hash_long(int32_t slot, int64_t self)
{
    int64_t v = aot_om_getter_j(slot, self);
    return (int32_t)(v ^ (v >> 32));
}

int32_t aot_om_hash_double(int32_t slot, int64_t self)
{
    int64_t bits = aot_om_getter_j(slot, self);
    double d = 0;
    memcpy(&d, &bits, sizeof(double));
    if (d == 0.0) {
        return 0;
    }
    return (int32_t)(bits ^ (bits >> 32));
}

int32_t aot_om_hash_bool(int32_t slot, int64_t self)
{
    return (int32_t)aot_om_getter_i(slot, self) != 0 ? 1231 : 1237;
}

int32_t aot_om_hash_ref(int32_t slot, int64_t self)
{
    int64_t v = aot_om_getter_j(slot, self);
    if (v == 0) {
        return 0;
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)v + 8);
    if (tid == aot_string_type_id) {
        return aot_str_hash(v);
    }
    return (int32_t)(intptr_t)v;
}

static int64_t aot_str_from_literal(const char *text)
{
    int32_t n = 0;
    while (text[n]) {
        n++;
    }
    return aot_str_from_utf8((int64_t)(intptr_t)text, n);
}

int64_t aot_om_str_int(int32_t slot, int64_t self)
{
    return aot_str_of_int((int32_t)aot_om_getter_i(slot, self));
}

int64_t aot_om_str_long(int32_t slot, int64_t self)
{
    return aot_str_of_long(aot_om_getter_j(slot, self));
}

int64_t aot_om_str_double(int32_t slot, int64_t self)
{
    int64_t bits = aot_om_getter_j(slot, self);
    double d = 0;
    memcpy(&d, &bits, sizeof(double));
    return aot_str_of_double(d);
}

int64_t aot_om_str_float(int32_t slot, int64_t self)
{
    int32_t bits = (int32_t)aot_om_getter_i(slot, self);
    float f = 0;
    memcpy(&f, &bits, sizeof(float));
    return aot_str_of_double((double)f);
}

int64_t aot_om_str_bool(int32_t slot, int64_t self)
{
    return aot_om_getter_i(slot, self) != 0 ? aot_str_from_literal("true") : aot_str_from_literal("false");
}

int64_t aot_om_str_char(int32_t slot, int64_t self)
{
    return aot_str_of_char((uint16_t)(aot_om_getter_i(slot, self) & 0xFFFF));
}

int64_t aot_om_str_ref(int32_t slot, int64_t self)
{
    int64_t v = aot_om_getter_j(slot, self);
    if (v == 0) {
        return aot_str_from_literal("null");
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)v + 8);
    if (tid == aot_string_type_id) {
        return v;
    }
    return aot_str_from_literal("<object>");
}

int64_t aot_jdk_new(int64_t class_name)
{
    int64_t obj = (int64_t)(intptr_t)gc_alloc(32);
    if (obj == 0) {
        return 0;
    }
    *(int64_t *)((char *)(intptr_t)obj + 16) = class_name;
    return obj;
}

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

int32_t aot_int_parse_ok(int64_t s)
{
    if (s == 0) {
        return 0;
    }
    aot_string *str = (aot_string *)(intptr_t)s;
    const uint16_t *c = (const uint16_t *)(intptr_t)str->chars;
    int32_t i = 0;
    if (str->length == 0) {
        return 0;
    }
    if (c[0] == '-' || c[0] == '+') {
        i = 1;
        if (str->length == 1) {
            return 0;
        }
    }
    while (i < str->length) {
        uint16_t ch = c[i];
        if (ch < '0' || ch > '9') {
            return 0;
        }
        i++;
    }
    return 1;
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

int64_t aot_box_char(int32_t v)
{
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_char_box_type_id;
    *(int32_t *)((char *)obj + 12) = 0;
    *(int32_t *)((char *)obj + 16) = v;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_box_bool(int32_t v)
{
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_bool_box_type_id;
    *(int32_t *)((char *)obj + 12) = 0;
    *(int32_t *)((char *)obj + 16) = v != 0 ? 1 : 0;
    return (int64_t)(intptr_t)obj;
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
    pthread_mutex_t guard;
    pthread_cond_t free_cv;
    pthread_cond_t wait_cv;
    pthread_t owner;
    int32_t depth;
    int32_t waiters;
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
    pthread_mutex_init(&e->guard, 0);
    pthread_cond_init(&e->free_cv, 0);
    pthread_cond_init(&e->wait_cv, 0);
    e->owner = 0;
    e->depth = 0;
    e->waiters = 0;
    e->next = monitors;
    monitors = e;
    pthread_mutex_unlock(&monitor_table_lock);
    return e;
}

static void monitor_acquire(monitor_entry *e)
{
    pthread_t self = pthread_self();
    pthread_mutex_lock(&e->guard);
    while (e->depth > 0 && !pthread_equal(e->owner, self)) {
        pthread_cond_wait(&e->free_cv, &e->guard);
    }
    e->owner = self;
    e->depth++;
    pthread_mutex_unlock(&e->guard);
}

static void monitor_release(monitor_entry *e)
{
    pthread_mutex_lock(&e->guard);
    if (e->depth > 0 && pthread_equal(e->owner, pthread_self())) {
        e->depth--;
        if (e->depth == 0) {
            e->owner = 0;
            pthread_cond_broadcast(&e->free_cv);
        }
    }
    pthread_mutex_unlock(&e->guard);
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
    monitor_acquire(e);
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
    monitor_release(e);
    return 1;
}

int64_t aot_monitor_depth(int64_t obj)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 0);
    if (!e) {
        return 0;
    }
    pthread_mutex_lock(&e->guard);
    int32_t d = e->depth;
    pthread_mutex_unlock(&e->guard);
    return (int64_t)d;
}

int32_t aot_monitor_holds(int64_t obj)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 0);
    if (!e) {
        return 0;
    }
    pthread_mutex_lock(&e->guard);
    int32_t held = e->depth > 0 && pthread_equal(e->owner, pthread_self());
    pthread_mutex_unlock(&e->guard);
    return held;
}

int32_t aot_monitor_wait(int64_t obj, int64_t timeout_ms, int32_t has_timeout)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 1);
    if (!e) {
        return -1;
    }
    int32_t saved = 0;
    pthread_mutex_lock(&e->guard);
    if (e->depth == 0 || !pthread_equal(e->owner, pthread_self())) {
        pthread_mutex_unlock(&e->guard);
        return -1;
    }
    saved = e->depth;
    e->depth = 0;
    e->owner = 0;
    e->waiters++;
    pthread_cond_broadcast(&e->free_cv);
    if (has_timeout && timeout_ms > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        int64_t ns = (int64_t)ts.tv_nsec + (timeout_ms % 1000) * 1000000LL;
        ts.tv_sec += (time_t)(timeout_ms / 1000) + (time_t)(ns / 1000000000LL);
        ts.tv_nsec = (long)(ns % 1000000000LL);
        pthread_cond_timedwait(&e->wait_cv, &e->guard, &ts);
    } else {
        pthread_cond_wait(&e->wait_cv, &e->guard);
    }
    e->waiters--;
    while (e->depth > 0) {
        pthread_cond_wait(&e->free_cv, &e->guard);
    }
    e->owner = pthread_self();
    e->depth = saved;
    pthread_mutex_unlock(&e->guard);
    return 1;
}

int32_t aot_monitor_notify(int64_t obj, int32_t all)
{
    if (obj == 0) {
        return 0;
    }
    monitor_entry *e = monitor_of(obj, 0);
    if (!e) {
        return -1;
    }
    pthread_mutex_lock(&e->guard);
    if (e->depth == 0 || !pthread_equal(e->owner, pthread_self())) {
        pthread_mutex_unlock(&e->guard);
        return -1;
    }
    if (e->waiters > 0) {
        if (all) {
            pthread_cond_broadcast(&e->wait_cv);
        } else {
            pthread_cond_signal(&e->wait_cv);
        }
    }
    pthread_mutex_unlock(&e->guard);
    return 1;
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
    int64_t *slots = (int64_t *)((char *)(intptr_t)arr + 24);
    for (int32_t i = 1; i < len; i++) {
        int32_t key = (int32_t)slots[i];
        int32_t j = i - 1;
        while (j >= 0 && (int32_t)slots[j] > key) {
            slots[j + 1] = slots[j];
            j--;
        }
        slots[j + 1] = key;
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
static int64_t fmt_buf;
static int32_t fmt_len;

static void fmt_reset(int32_t cap)
{
    fmt_buf = str_alloc(cap);
    fmt_len = 0;
}

static aot_string *fmt_str(void)
{
    return (aot_string *)(intptr_t)fmt_buf;
}

static void fmt_put(uint16_t ch)
{
    aot_string *s = fmt_str();
    if (fmt_len >= s->length) {
        int32_t ncap = s->length * 2 + 8;
        int64_t nb = str_alloc(ncap);
        uint16_t *src = (uint16_t *)(intptr_t)s->chars;
        uint16_t *dst = (uint16_t *)(intptr_t)((aot_string *)(intptr_t)nb)->chars;
        for (int32_t i = 0; i < fmt_len; i++) {
            dst[i] = src[i];
        }
        fmt_buf = nb;
        s = fmt_str();
    }
    ((uint16_t *)(intptr_t)s->chars)[fmt_len++] = ch;
}

static void fmt_puts(const uint16_t *src, int32_t n)
{
    for (int32_t i = 0; i < n; i++) {
        fmt_put(src[i]);
    }
}

static void fmt_put_str(int64_t sv)
{
    if (sv == 0) {
        return;
    }
    aot_string *s = (aot_string *)(intptr_t)sv;
    fmt_puts((const uint16_t *)(intptr_t)s->chars, s->length);
}

static void fmt_put_padded(int64_t sv, int32_t width, int32_t zero)
{
    int32_t n = aot_str_length(sv);
    if (width > n) {
        int32_t pad = width - n;
        for (int32_t i = 0; i < pad; i++) {
            fmt_put(zero ? '0' : ' ');
        }
    }
    fmt_put_str(sv);
}

static int64_t fmt_hex(uint32_t v)
{
    uint16_t buf[16];
    int32_t p = 0;
    if (v == 0) {
        buf[p++] = '0';
    }
    while (v > 0 && p < 16) {
        uint32_t d = v & 0xF;
        buf[p++] = (uint16_t)(d < 10 ? ('0' + d) : ('a' + d - 10));
        v >>= 4;
    }
    int64_t out = str_alloc(p);
    if (out == 0) {
        return 0;
    }
    uint16_t *dst = (uint16_t *)(intptr_t)((aot_string *)(intptr_t)out)->chars;
    for (int32_t i = 0; i < p; i++) {
        dst[i] = buf[p - 1 - i];
    }
    return out;
}

int64_t aot_str_format(int64_t fmt, int64_t args)
{
    int32_t n = aot_str_length(fmt);
    if (fmt == 0) {
        return str_alloc(0);
    }
    aot_string *f = (aot_string *)(intptr_t)fmt;
    const uint16_t *fc = (const uint16_t *)(intptr_t)f->chars;
    int32_t argc = 0;
    int64_t *av = 0;
    if (args != 0) {
        argc = *(int32_t *)((char *)(intptr_t)args - 8);
        av = (int64_t *)(intptr_t)args;
    }
    fmt_reset(n + 16);
    int32_t argi = 0;
    int32_t i = 0;
    while (i < n) {
        uint16_t ch = fc[i];
        if (ch != '%') {
            fmt_put(ch);
            i++;
            continue;
        }
        i++;
        if (i >= n) {
            fmt_put('%');
            break;
        }
        if (fc[i] == '%') {
            fmt_put('%');
            i++;
            continue;
        }
        int32_t zero = 0;
        int32_t width = 0;
        if (fc[i] == '0') {
            zero = 1;
            i++;
        }
        while (i < n && fc[i] >= '0' && fc[i] <= '9') {
            width = width * 10 + (fc[i] - '0');
            i++;
        }
        if (i < n && fc[i] == 'l') {
            i++;
        }
        if (i >= n) {
            break;
        }
        uint16_t cv = fc[i];
        i++;
        int64_t a = 0;
        if (argi < argc) {
            a = av[argi];
        }
        argi++;
        if (cv != 's' && a != 0) {
            int32_t atid = *(int32_t *)((char *)(intptr_t)a + 8);
            if (atid == aot_integer_type_id) {
                a = (int64_t)*(int32_t *)((char *)(intptr_t)a + 16);
            } else if (atid == aot_long_type_id) {
                a = *(int64_t *)((char *)(intptr_t)a + 16);
            } else if (atid == aot_char_box_type_id) {
                a = (int64_t)*(int32_t *)((char *)(intptr_t)a + 16);
            } else if (atid == aot_bool_box_type_id) {
                a = (int64_t)*(int32_t *)((char *)(intptr_t)a + 16);
            }
        }
        if (cv == 'd' || cv == 'd') {
            fmt_put_padded(aot_str_of_int((int32_t)a), width, zero);
        } else if (cv == 'x') {
            fmt_put_padded(fmt_hex((uint32_t)a), width, zero);
        } else if (cv == 'c') {
            fmt_put((uint16_t)(a & 0xFFFF));
        } else if (cv == 'b') {
            fmt_put_padded(aot_str_of_bool((int32_t)a), width, zero);
        } else if (cv == 's') {
            int64_t s = a;
            if (s != 0) {
                int32_t tid = *(int32_t *)((char *)(intptr_t)s + 8);
                if (tid != aot_string_type_id) {
                    s = aot_str_of_object(s);
                }
            }
            fmt_put_padded(s, width, zero);
        } else if (cv == 'f') {
            fmt_put_padded(aot_str_of_double(0.0), width, zero);
        } else {
            fmt_put('%');
            fmt_put(cv);
        }
    }
    aot_string *o = fmt_str();
    o->length = fmt_len;
    return fmt_buf;
}

typedef struct { void *handle; } ffi_lib;

#define AOT_SYM_CAP 4096

typedef struct {
    char name[96];
    void *addr;
} aot_sym_entry;

static aot_sym_entry aot_sym_tab[AOT_SYM_CAP];
static int32_t aot_sym_count;

void aot_sym_register(const char *name, void *addr)
{
    if (!name || !addr || aot_sym_count >= AOT_SYM_CAP) {
        return;
    }
    int32_t n = 0;
    while (name[n] && n < 95) {
        aot_sym_tab[aot_sym_count].name[n] = name[n];
        n++;
    }
    aot_sym_tab[aot_sym_count].name[n] = 0;
    aot_sym_tab[aot_sym_count].addr = addr;
    aot_sym_count++;
}

static int aot_sym_name_eq(const char *a, const char *b)
{
    int i = 0;
    while (a[i] && b[i]) {
        if (a[i] != b[i]) {
            return 0;
        }
        i++;
    }
    return a[i] == 0 && b[i] == 0;
}

static void *aot_sym_lookup_static(const char *name)
{
    for (int32_t i = 0; i < aot_sym_count; i++) {
        if (aot_sym_name_eq(aot_sym_tab[i].name, name)) {
            return aot_sym_tab[i].addr;
        }
    }
    return 0;
}


typedef struct {
    int64_t user_ptr;
    int64_t size;
    char *base;
} direct_buf;

static int aot_lib_name_is(const char *a, const char *b)
{
    int i = 0;
    while (a[i] && b[i]) {
        if (a[i] != b[i]) {
            return 0;
        }
        i++;
    }
    return a[i] == 0 && b[i] == 0;
}

static void *aot_dlopen_portable(const char *name)
{
    void *h = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        return h;
    }
    if (aot_lib_name_is(name, "libc.so") || aot_lib_name_is(name, "libc.so.6")) {
        static const char *const cand[] = {"/usr/lib/libSystem.B.dylib", "libSystem.B.dylib", 0};
        for (int i = 0; cand[i]; i++) {
            h = dlopen(cand[i], RTLD_NOW | RTLD_GLOBAL);
            if (h) {
                return h;
            }
        }
        return RTLD_DEFAULT;
    }
    if (aot_lib_name_is(name, "libm.so") || aot_lib_name_is(name, "libm.so.6")) {
        h = dlopen("/usr/lib/libSystem.B.dylib", RTLD_NOW | RTLD_GLOBAL);
        if (h) {
            return h;
        }
        return RTLD_DEFAULT;
    }
    if (aot_lib_name_is(name, "libdl.so") || aot_lib_name_is(name, "libdl.so.2")) {
        return RTLD_DEFAULT;
    }
    if (aot_lib_name_is(name, "libpthread.so") || aot_lib_name_is(name, "libpthread.so.0")) {
        return RTLD_DEFAULT;
    }
    return 0;
}

int64_t aot_ffi_open(int64_t path)
{
    if (path == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)path;
    const uint16_t *cs = (const uint16_t *)(intptr_t)s->chars;
    char name[1024];
    int32_t n = s->length < 1000 ? s->length : 1000;
    for (int32_t i = 0; i < n; i++) {
        name[i] = (char)cs[i];
    }
    name[n] = 0;
    ffi_lib *lib = (ffi_lib *)malloc(sizeof(ffi_lib));
    if (!lib) {
        return 0;
    }
    lib->handle = aot_dlopen_portable(name);
    if (!lib->handle) {
        free(lib);
        return 0;
    }
    return (int64_t)(intptr_t)lib;
}

int64_t aot_ffi_sym_nohandle(int64_t name)
{
    if (name == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)name;
    const uint16_t *cs = (const uint16_t *)(intptr_t)s->chars;
    char nm[1024];
    int32_t n = s->length < 1000 ? s->length : 1000;
    for (int32_t i = 0; i < n; i++) {
        nm[i] = (char)cs[i];
    }
    nm[n] = 0;
    void *p = aot_sym_lookup_static(nm);
    if (p) {
        return (int64_t)(intptr_t)p;
    }
    p = dlsym(RTLD_DEFAULT, nm);
    return (int64_t)(intptr_t)p;
}

int64_t aot_ffi_sym(int64_t libh, int64_t name)
{
    if (name == 0) {
        return 0;
    }
    aot_string *s = (aot_string *)(intptr_t)name;
    const uint16_t *cs = (const uint16_t *)(intptr_t)s->chars;
    char nm[1024];
    int32_t n = s->length < 1000 ? s->length : 1000;
    for (int32_t i = 0; i < n; i++) {
        nm[i] = (char)cs[i];
    }
    nm[n] = 0;
    void *p = aot_sym_lookup_static(nm);
    if (p) {
        return (int64_t)(intptr_t)p;
    }
    if (libh != 0) {
        ffi_lib *lib = (ffi_lib *)(intptr_t)libh;
        p = dlsym(lib->handle, nm);
        if (p) {
            return (int64_t)(intptr_t)p;
        }
    }
    p = dlsym(RTLD_DEFAULT, nm);
    return (int64_t)(intptr_t)p;
}

int64_t aot_ffi_call0(int64_t fn)
{
    int64_t (*f)(void) = (int64_t (*)(void))(intptr_t)fn;
    return f();
}

int64_t aot_ffi_call1(int64_t fn, int64_t a)
{
    int64_t (*f)(int64_t) = (int64_t (*)(int64_t))(intptr_t)fn;
    return f(a);
}

int64_t aot_ffi_call2(int64_t fn, int64_t a, int64_t b)
{
    int64_t (*f)(int64_t, int64_t) = (int64_t (*)(int64_t, int64_t))(intptr_t)fn;
    return f(a, b);
}

int64_t aot_ffi_call3(int64_t fn, int64_t a, int64_t b, int64_t c)
{
    int64_t (*f)(int64_t, int64_t, int64_t) = (int64_t (*)(int64_t, int64_t, int64_t))(intptr_t)fn;
    return f(a, b, c);
}

int64_t aot_ffi_call4(int64_t fn, int64_t a, int64_t b, int64_t c, int64_t d)
{
    int64_t (*f)(int64_t, int64_t, int64_t, int64_t) = (int64_t (*)(int64_t, int64_t, int64_t, int64_t))(intptr_t)fn;
    return f(a, b, c, d);
}

int64_t aot_ffi_call5(int64_t fn, int64_t a, int64_t b, int64_t c, int64_t d, int64_t e)
{
    int64_t (*f)(int64_t, int64_t, int64_t, int64_t, int64_t) = (int64_t (*)(int64_t, int64_t, int64_t, int64_t, int64_t))(intptr_t)fn;
    return f(a, b, c, d, e);
}

int64_t aot_ffi_call6(int64_t fn, int64_t a, int64_t b, int64_t c, int64_t d, int64_t e, int64_t g)
{
    int64_t (*f)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t) = (int64_t (*)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t))(intptr_t)fn;
    return f(a, b, c, d, e, g);
}

int64_t aot_mem_alloc(int64_t size)
{
    if (size <= 0) {
        return 0;
    }
    direct_buf *b = (direct_buf *)malloc(sizeof(direct_buf));
    if (!b) {
        return 0;
    }
    b->base = (char *)calloc((size_t)size, 1);
    if (!b->base) {
        free(b);
        return 0;
    }
    b->size = size;
    b->user_ptr = (int64_t)(intptr_t)b->base;
    return (int64_t)(intptr_t)b;
}

int64_t aot_mem_addr(int64_t h)
{
    if (h == 0) {
        return 0;
    }
    return ((direct_buf *)(intptr_t)h)->user_ptr;
}

int64_t aot_mem_size(int64_t h)
{
    if (h == 0) {
        return 0;
    }
    return ((direct_buf *)(intptr_t)h)->size;
}

void aot_mem_free(int64_t h)
{
    if (h == 0) {
        return;
    }
    direct_buf *b = (direct_buf *)(intptr_t)h;
    free(b->base);
    free(b);
}

void aot_mem_put8(int64_t h, int64_t off, int64_t v)
{
    if (h == 0 || off < 0) {
        return;
    }
    direct_buf *b = (direct_buf *)(intptr_t)h;
    if (off + 8 > b->size) {
        return;
    }
    int64_t *p = (int64_t *)(b->base + off);
    *p = v;
}

void aot_mem_put4(int64_t h, int64_t off, int32_t v)
{
    if (h == 0 || off < 0) {
        return;
    }
    direct_buf *b = (direct_buf *)(intptr_t)h;
    if (off + 4 > b->size) {
        return;
    }
    int32_t *p = (int32_t *)(b->base + off);
    *p = v;
}

int64_t aot_mem_get8(int64_t h, int64_t off)
{
    if (h == 0 || off < 0) {
        return 0;
    }
    direct_buf *b = (direct_buf *)(intptr_t)h;
    if (off + 8 > b->size) {
        return 0;
    }
    return *(int64_t *)(b->base + off);
}

int32_t aot_mem_get4(int64_t h, int64_t off)
{
    if (h == 0 || off < 0) {
        return 0;
    }
    direct_buf *b = (direct_buf *)(intptr_t)h;
    if (off + 4 > b->size) {
        return 0;
    }
    return *(int32_t *)(b->base + off);
}

int32_t aot_addr_get4(int64_t addr, int64_t off)
{
    if (addr == 0 || off < 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)addr + off);
}

int64_t aot_addr_get8(int64_t addr, int64_t off)
{
    if (addr == 0 || off < 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)addr + off);
}

void aot_addr_put4(int64_t addr, int64_t off, int32_t v)
{
    if (addr == 0 || off < 0) {
        return;
    }
    *(int32_t *)((char *)(intptr_t)addr + off) = v;
}

void aot_addr_put8(int64_t addr, int64_t off, int64_t v)
{
    if (addr == 0 || off < 0) {
        return;
    }
    *(int64_t *)((char *)(intptr_t)addr + off) = v;
}

void aot_mem_copy(int64_t dst_addr, int64_t src_addr, int64_t n)
{
    if (dst_addr == 0 || src_addr == 0 || n <= 0) {
        return;
    }
    memcpy((void *)(intptr_t)dst_addr, (void *)(intptr_t)src_addr, (size_t)n);
}

void aot_addr_put8_at(int64_t addr, int64_t v)
{
    if (addr == 0) {
        return;
    }
    *(int64_t *)(intptr_t)addr = v;
}

void aot_addr_put4_at(int64_t addr, int32_t v)
{
    if (addr == 0) {
        return;
    }
    *(int32_t *)(intptr_t)addr = v;
}

int64_t aot_addr_get8_at(int64_t addr)
{
    if (addr == 0) {
        return 0;
    }
    return *(int64_t *)(intptr_t)addr;
}

int32_t aot_addr_get4_at(int64_t addr)
{
    if (addr == 0) {
        return 0;
    }
    return *(int32_t *)(intptr_t)addr;
}

#define FK_VOID 0
#define FK_I32 1
#define FK_I64 2
#define FK_F32 3
#define FK_F64 4
#define FK_PTR 5

static void ffi_type_for(int32_t k, ffi_type **t)
{
    switch (k) {
        case FK_I32: *t = &ffi_type_sint32; break;
        case FK_F32: *t = &ffi_type_float; break;
        case FK_F64: *t = &ffi_type_double; break;
        case FK_I64: *t = &ffi_type_sint64; break;
        default: *t = &ffi_type_pointer; break;
    }
}

static int64_t libffi_call(int64_t fn, int32_t retk, int64_t *slots, int32_t nargs, int32_t *argk)
{
    if (fn == 0) {
        return 0;
    }
    ffi_type *rt;
    if (retk == FK_VOID) {
        rt = &ffi_type_void;
    } else {
        ffi_type_for(retk, &rt);
    }
    ffi_type **at = 0;
    void **av = 0;
    if (nargs > 0) {
        at = (ffi_type **)calloc((size_t)nargs, sizeof(ffi_type *));
        av = (void **)calloc((size_t)nargs, sizeof(void *));
        if (!at || !av) {
            free(at);
            free(av);
            return 0;
        }
    }
    union {
        int32_t i32v;
        int64_t i64v;
        float f32v;
        double f64v;
        void *pv;
    } cells[16];
    int32_t n = nargs > 16 ? 16 : nargs;
    for (int32_t i = 0; i < n; i++) {
        ffi_type_for(argk[i], &at[i]);
        int64_t raw = slots[i];
        switch (argk[i]) {
            case FK_I32:
                cells[i].i32v = (int32_t)raw;
                av[i] = &cells[i].i32v;
                break;
            case FK_F32:
                memcpy(&cells[i].f32v, &raw, sizeof(float));
                av[i] = &cells[i].f32v;
                break;
            case FK_F64:
                memcpy(&cells[i].f64v, &raw, sizeof(double));
                av[i] = &cells[i].f64v;
                break;
            case FK_I64:
                cells[i].i64v = raw;
                av[i] = &cells[i].i64v;
                break;
            default:
                cells[i].pv = (void *)(intptr_t)raw;
                av[i] = &cells[i].pv;
                break;
        }
    }
    ffi_cif cif;
    if (ffi_prep_cif(&cif, FFI_DEFAULT_ABI, (unsigned int)n, rt, at) != FFI_OK) {
        free(at);
        free(av);
        return 0;
    }
    int64_t out = 0;
    if (retk == FK_VOID) {
        ffi_call(&cif, FFI_FN((void *)(intptr_t)fn), 0, av);
    } else if (retk == FK_I32) {
        int32_t rv = 0;
        ffi_call(&cif, FFI_FN((void *)(intptr_t)fn), &rv, av);
        out = (int64_t)rv;
    } else if (retk == FK_F32) {
        float rv = 0;
        ffi_call(&cif, FFI_FN((void *)(intptr_t)fn), &rv, av);
        int32_t bits = 0;
        memcpy(&bits, &rv, sizeof(float));
        out = (int64_t)bits;
    } else if (retk == FK_F64) {
        double rv = 0;
        ffi_call(&cif, FFI_FN((void *)(intptr_t)fn), &rv, av);
        memcpy(&out, &rv, sizeof(double));
    } else {
        void *rv = 0;
        ffi_call(&cif, FFI_FN((void *)(intptr_t)fn), &rv, av);
        out = (int64_t)(intptr_t)rv;
    }
    free(at);
    free(av);
    return out;
}

int64_t aot_ffi_invoke(int64_t fn, int32_t retk, int64_t slots, int32_t nargs, int64_t kinds)
{
    int32_t kinds32[16];
    int32_t n = nargs > 16 ? 16 : nargs;
    int64_t *ks = (int64_t *)(intptr_t)kinds;
    for (int32_t i = 0; i < n; i++) {
        kinds32[i] = (int32_t)ks[i];
    }
    return libffi_call(fn, retk, (int64_t *)(intptr_t)slots, nargs, kinds32);
}

void aot_addr_putf_at(int64_t addr, float v)
{
    if (addr == 0) {
        return;
    }
    *(float *)(intptr_t)addr = v;
}

void aot_addr_putd_at(int64_t addr, double v)
{
    if (addr == 0) {
        return;
    }
    *(double *)(intptr_t)addr = v;
}

float aot_addr_getf_at(int64_t addr)
{
    if (addr == 0) {
        return 0.0f;
    }
    return *(float *)(intptr_t)addr;
}

double aot_addr_getd_at(int64_t addr)
{
    if (addr == 0) {
        return 0.0;
    }
    return *(double *)(intptr_t)addr;
}

void aot_addr_put1_at(int64_t addr, int32_t v)
{
    if (addr == 0) {
        return;
    }
    *(int8_t *)(intptr_t)addr = (int8_t)v;
}

void aot_addr_put2_at(int64_t addr, int32_t v)
{
    if (addr == 0) {
        return;
    }
    *(int16_t *)(intptr_t)addr = (int16_t)v;
}

int32_t aot_addr_get1_at(int64_t addr)
{
    if (addr == 0) {
        return 0;
    }
    return *(int8_t *)(intptr_t)addr;
}

int32_t aot_addr_get2_at(int64_t addr)
{
    if (addr == 0) {
        return 0;
    }
    return *(int16_t *)(intptr_t)addr;
}

int64_t aot_mem_set(int64_t addr, int64_t n, int32_t v)
{
    if (addr == 0 || n <= 0) {
        return addr;
    }
    memset((void *)(intptr_t)addr, v, (size_t)n);
    return addr;
}

static __thread char *stack_arena;
static __thread int64_t stack_size;
static __thread int64_t stack_top;

int64_t aot_stack_push(void)
{
    int64_t mark = stack_top;
    if (stack_arena == 0) {
        stack_size = 64 * 1024;
        stack_arena = (char *)malloc((size_t)stack_size);
        stack_top = 0;
        mark = 0;
    }
    return mark;
}

void aot_stack_pop(void)
{
    if (stack_arena != 0) {
        free(stack_arena);
        stack_arena = 0;
        stack_size = 0;
        stack_top = 0;
    }
}

int64_t aot_stack_malloc(int64_t n)
{
    if (n <= 0) {
        return 0;
    }
    if (stack_arena == 0) {
        stack_size = 64 * 1024;
        stack_arena = (char *)malloc((size_t)stack_size);
        stack_top = 0;
    }
    int64_t aligned = (n + 15) & ~15LL;
    if (stack_top + aligned > stack_size) {
        return 0;
    }
    int64_t p = (int64_t)(intptr_t)(stack_arena + stack_top);
    stack_top += aligned;
    return p;
}

int32_t aot_double_box_type_id;
int32_t aot_float_box_type_id;

void aot_set_double_box_type(int32_t t)
{
    aot_double_box_type_id = t;
}

void aot_set_float_box_type(int32_t t)
{
    aot_float_box_type_id = t;
}

int64_t aot_box_double(int64_t bits)
{
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_double_box_type_id;
    *(int32_t *)((char *)obj + 12) = 0;
    *(int64_t *)((char *)obj + 16) = bits;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_unbox_double(int64_t o)
{
    if (o == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)o + 16);
}

int64_t aot_box_float(int32_t bits)
{
    void *obj = gc_alloc(24);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = aot_float_box_type_id;
    *(int32_t *)((char *)obj + 12) = 0;
    *(int32_t *)((char *)obj + 16) = bits;
    return (int64_t)(intptr_t)obj;
}

int32_t aot_unbox_float(int64_t o)
{
    if (o == 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)o + 16);
}

static int32_t aot_buf_byte_tid;
static int32_t aot_buf_short_tid;
static int32_t aot_buf_char_tid;
static int32_t aot_buf_int_tid;
static int32_t aot_buf_long_tid;
static int32_t aot_buf_float_tid;
static int32_t aot_buf_double_tid;
static int32_t aot_buf_ptr_tid;

void aot_set_buf_types(int32_t b, int32_t s, int32_t c, int32_t i, int32_t l, int32_t f, int32_t d, int32_t p)
{
    aot_buf_byte_tid = b;
    aot_buf_short_tid = s;
    aot_buf_char_tid = c;
    aot_buf_int_tid = i;
    aot_buf_long_tid = l;
    aot_buf_float_tid = f;
    aot_buf_double_tid = d;
    aot_buf_ptr_tid = p;
}

static int32_t buf_elem_size(int32_t tid)
{
    switch (tid) {
        case 0: return 1;
        case 1: case 2: return 2;
        case 3: case 5: return 4;
        default: return 8;
    }
}

static int64_t buf_new(int32_t tid, int64_t addr, int32_t cap, int32_t elem)
{
    void *obj = gc_alloc(40);
    if (!obj) {
        return 0;
    }
    *(void **)(char *)obj = aot_string_vtable;
    *(int32_t *)((char *)obj + 8) = tid;
    *(int32_t *)((char *)obj + 12) = elem;
    *(int64_t *)((char *)obj + 16) = addr;
    *(int32_t *)((char *)obj + 24) = cap;
    *(int32_t *)((char *)obj + 28) = 0;
    *(int32_t *)((char *)obj + 32) = cap;
    return (int64_t)(intptr_t)obj;
}

int64_t aot_buf_alloc(int32_t tid, int32_t cap)
{
    if (cap < 0) {
        return 0;
    }
    int32_t es = buf_elem_size(tid);
    int64_t bytes = (int64_t)cap * es;
    if (bytes <= 0) {
        bytes = 1;
    }
    char *mem = (char *)calloc((size_t)bytes, 1);
    if (!mem) {
        return 0;
    }
    return buf_new(tid, (int64_t)(intptr_t)mem, cap, es);
}

int64_t aot_buf_wrap(int32_t tid, int64_t addr, int32_t cap)
{
    return buf_new(tid, addr, cap, buf_elem_size(tid));
}

int64_t aot_buf_addr(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)b + 16);
}

int32_t aot_buf_capacity(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)b + 24);
}

int32_t aot_buf_position(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)b + 28);
}

int32_t aot_buf_limit(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    return *(int32_t *)((char *)(intptr_t)b + 32);
}

int64_t aot_buf_set_position(int64_t b, int32_t p)
{
    if (b == 0) {
        return b;
    }
    int32_t cap = *(int32_t *)((char *)(intptr_t)b + 24);
    if (p < 0 || p > cap) {
        return b;
    }
    *(int32_t *)((char *)(intptr_t)b + 28) = p;
    return b;
}

int64_t aot_buf_set_limit(int64_t b, int32_t l)
{
    if (b == 0) {
        return b;
    }
    int32_t cap = *(int32_t *)((char *)(intptr_t)b + 24);
    if (l < 0 || l > cap) {
        return b;
    }
    *(int32_t *)((char *)(intptr_t)b + 32) = l;
    return b;
}

int32_t aot_buf_remaining(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    int32_t lim = *(int32_t *)((char *)(intptr_t)b + 32);
    int32_t pos = *(int32_t *)((char *)(intptr_t)b + 28);
    return lim - pos;
}

int32_t aot_buf_has_remaining(int64_t b)
{
    return aot_buf_remaining(b) > 0;
}

int64_t aot_buf_flip(int64_t b)
{
    if (b == 0) {
        return b;
    }
    int32_t pos = *(int32_t *)((char *)(intptr_t)b + 28);
    *(int32_t *)((char *)(intptr_t)b + 32) = pos;
    *(int32_t *)((char *)(intptr_t)b + 28) = 0;
    return b;
}

int64_t aot_buf_rewind(int64_t b)
{
    if (b == 0) {
        return b;
    }
    *(int32_t *)((char *)(intptr_t)b + 28) = 0;
    return b;
}

int64_t aot_buf_clear(int64_t b)
{
    if (b == 0) {
        return b;
    }
    int32_t cap = *(int32_t *)((char *)(intptr_t)b + 24);
    *(int32_t *)((char *)(intptr_t)b + 28) = 0;
    *(int32_t *)((char *)(intptr_t)b + 32) = cap;
    return b;
}

static char *buf_at(int64_t b, int32_t idx)
{
    int64_t addr = *(int64_t *)((char *)(intptr_t)b + 16);
    int32_t es = *(int32_t *)((char *)(intptr_t)b + 12);
    return (char *)(intptr_t)addr + (int64_t)idx * es;
}

int64_t aot_buf_get(int64_t b, int32_t idx)
{
    if (b == 0 || idx < 0) {
        return 0;
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)b + 8);
    char *p = buf_at(b, idx);
    switch (tid) {
        case 0: return (int64_t)(*(int8_t *)p);
        case 1: return (int64_t)(*(int16_t *)p);
        case 2: return (int64_t)(*(uint16_t *)p);
        case 3: return (int64_t)(*(int32_t *)p);
        case 5: {
            float f = *(float *)p;
            int32_t bits = 0;
            memcpy(&bits, &f, sizeof(float));
            return (int64_t)bits;
        }
        case 6: {
            double d = *(double *)p;
            int64_t bits = 0;
            memcpy(&bits, &d, sizeof(double));
            return bits;
        }
        default: return *(int64_t *)p;
    }
}

int64_t aot_buf_get_advance(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    int32_t pos = *(int32_t *)((char *)(intptr_t)b + 28);
    int32_t lim = *(int32_t *)((char *)(intptr_t)b + 32);
    if (pos >= lim) {
        return 0;
    }
    *(int32_t *)((char *)(intptr_t)b + 28) = pos + 1;
    return aot_buf_get(b, pos);
}

void aot_buf_put(int64_t b, int32_t idx, int64_t v)
{
    if (b == 0 || idx < 0) {
        return;
    }
    int32_t tid = *(int32_t *)((char *)(intptr_t)b + 8);
    char *p = buf_at(b, idx);
    switch (tid) {
        case 0: *(int8_t *)p = (int8_t)v; return;
        case 1: *(int16_t *)p = (int16_t)v; return;
        case 2: *(uint16_t *)p = (uint16_t)v; return;
        case 3: *(int32_t *)p = (int32_t)v; return;
        case 5: {
            int32_t bits = (int32_t)v;
            float f = 0;
            memcpy(&f, &bits, sizeof(float));
            *(float *)p = f;
            return;
        }
        case 6: {
            double d = 0;
            memcpy(&d, &v, sizeof(double));
            *(double *)p = d;
            return;
        }
        default: *(int64_t *)p = v; return;
    }
}

int64_t aot_buf_put_advance(int64_t b, int64_t v)
{
    if (b == 0) {
        return b;
    }
    int32_t pos = *(int32_t *)((char *)(intptr_t)b + 28);
    int32_t lim = *(int32_t *)((char *)(intptr_t)b + 32);
    if (pos >= lim) {
        return b;
    }
    *(int32_t *)((char *)(intptr_t)b + 28) = pos + 1;
    aot_buf_put(b, pos, v);
    return b;
}

int64_t aot_buf_read(int64_t b, int32_t off, int32_t width)
{
    if (b == 0 || off < 0) {
        return 0;
    }
    int64_t addr = *(int64_t *)((char *)(intptr_t)b + 16);
    int32_t cap = *(int32_t *)((char *)(intptr_t)b + 24);
    if (addr == 0 || off + width > cap) {
        return 0;
    }
    char *p = (char *)(intptr_t)addr + off;
    switch (width) {
        case 1: return (int64_t)(*(int8_t *)p);
        case 2: return (int64_t)(*(int16_t *)p);
        case 4: {
            if ((*(int32_t *)((char *)(intptr_t)b + 8)) == 5) {
                float f = *(float *)p;
                int32_t bits = 0;
                memcpy(&bits, &f, sizeof(float));
                return (int64_t)bits;
            }
            return (int64_t)(*(int32_t *)p);
        }
        default: {
            if ((*(int32_t *)((char *)(intptr_t)b + 8)) == 6) {
                double d = *(double *)p;
                int64_t bits = 0;
                memcpy(&bits, &d, sizeof(double));
                return bits;
            }
            return *(int64_t *)p;
        }
    }
}

void aot_buf_write(int64_t b, int32_t off, int32_t width, int64_t v)
{
    if (b == 0 || off < 0) {
        return;
    }
    int64_t addr = *(int64_t *)((char *)(intptr_t)b + 16);
    int32_t cap = *(int32_t *)((char *)(intptr_t)b + 24);
    if (addr == 0 || off + width > cap) {
        return;
    }
    char *p = (char *)(intptr_t)addr + off;
    int32_t tid = *(int32_t *)((char *)(intptr_t)b + 8);
    switch (width) {
        case 1: *(int8_t *)p = (int8_t)v; return;
        case 2: *(int16_t *)p = (int16_t)v; return;
        case 4: {
            if (tid == 5) {
                int32_t bits = (int32_t)v;
                float f = 0;
                memcpy(&f, &bits, sizeof(float));
                *(float *)p = f;
                return;
            }
            *(int32_t *)p = (int32_t)v;
            return;
        }
        default: {
            if (tid == 6) {
                double d = 0;
                memcpy(&d, &v, sizeof(double));
                *(double *)p = d;
                return;
            }
            *(int64_t *)p = v;
            return;
        }
    }
}

int64_t aot_buf_arg_addr(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    return *(int64_t *)((char *)(intptr_t)b + 16);
}

int64_t aot_buf_arg_bytes(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    int32_t elem = *(int32_t *)((char *)(intptr_t)b + 12);
    int32_t rem = aot_buf_remaining(b);
    return (int64_t)elem * rem;
}

int64_t aot_buf_arg_pos_addr(int64_t b)
{
    if (b == 0) {
        return 0;
    }
    int64_t base = *(int64_t *)((char *)(intptr_t)b + 16);
    int32_t elem = *(int32_t *)((char *)(intptr_t)b + 12);
    int32_t pos = *(int32_t *)((char *)(intptr_t)b + 28);
    return base + (int64_t)elem * pos;
}

int32_t aot_ffi_call_buf1(int64_t fn, int64_t addr)
{
    if (fn == 0) {
        return 0;
    }
    int32_t (*f)(int64_t) = (int32_t (*)(int64_t))(intptr_t)fn;
    return f(addr);
}

int32_t aot_ffi_call_buf2(int64_t fn, int32_t a, int64_t addr)
{
    if (fn == 0) {
        return 0;
    }
    int32_t (*f)(int32_t, int64_t) = (int32_t (*)(int32_t, int64_t))(intptr_t)fn;
    return f(a, addr);
}

typedef struct {
    ffi_cif cif;
    ffi_type **at;
    ffi_type *rt;
    void *code;
    ffi_closure *closure;
    int64_t target;
    int32_t retk;
    int32_t nargs;
    int32_t argk[16];
} aot_closure;

static void aot_closure_tramp(ffi_cif *cif, void *ret, void **args, void *ud)
{
    aot_closure *c = (aot_closure *)ud;
    int64_t slots[16];
    int32_t n = c->nargs;
    for (int32_t i = 0; i < n; i++) {
        switch (c->argk[i]) {
            case FK_I32:
                slots[i] = (int64_t)(*(int32_t *)args[i]);
                break;
            case FK_F32: {
                float f = *(float *)args[i];
                int32_t bits = 0;
                memcpy(&bits, &f, sizeof(float));
                slots[i] = (int64_t)bits;
                break;
            }
            case FK_F64: {
                double d = *(double *)args[i];
                int64_t bits = 0;
                memcpy(&bits, &d, sizeof(double));
                slots[i] = bits;
                break;
            }
            case FK_I64:
                slots[i] = *(int64_t *)args[i];
                break;
            default:
                slots[i] = (int64_t)(intptr_t)(*(void **)args[i]);
                break;
        }
    }
    int64_t (*target)(int64_t *) = (int64_t (*)(int64_t *))(intptr_t)c->target;
    int64_t out = target(slots);
    if (c->retk == FK_VOID) {
        return;
    }
    if (c->retk == FK_I32) {
        *(int32_t *)ret = (int32_t)out;
        return;
    }
    if (c->retk == FK_F32) {
        int32_t bits = (int32_t)out;
        float f = 0;
        memcpy(&f, &bits, sizeof(float));
        *(float *)ret = f;
        return;
    }
    if (c->retk == FK_F64) {
        double d = 0;
        memcpy(&d, &out, sizeof(double));
        *(double *)ret = d;
        return;
    }
    *(void **)ret = (void *)(intptr_t)out;
}

int64_t aot_closure_new(int64_t target, int32_t retk, int64_t kinds, int32_t nargs)
{
    aot_closure *c = (aot_closure *)calloc(1, sizeof(aot_closure));
    if (!c) {
        return 0;
    }
    c->target = target;
    c->retk = retk;
    c->nargs = nargs > 16 ? 16 : nargs;
    int32_t *kk = (int32_t *)(intptr_t)kinds;
    ffi_type **at = (ffi_type **)calloc((size_t)(c->nargs > 0 ? c->nargs : 1), sizeof(ffi_type *));
    if (!at) {
        free(c);
        return 0;
    }
    for (int32_t i = 0; i < c->nargs; i++) {
        c->argk[i] = kk[i * 2];
        ffi_type_for(c->argk[i], &at[i]);
    }
    ffi_type *rt = 0;
    ffi_type_for(retk, &rt);
    if (ffi_prep_cif(&c->cif, FFI_DEFAULT_ABI, (unsigned int)c->nargs, rt, at) != FFI_OK) {
        free(at);
        free(c);
        return 0;
    }
    c->at = at;
    c->rt = rt;
    c->closure = (ffi_closure *)ffi_closure_alloc(sizeof(ffi_closure), &c->code);
    if (!c->closure) {
        free(at);
        free(c);
        return 0;
    }
    if (ffi_prep_closure_loc(c->closure, &c->cif, aot_closure_tramp, c, c->code) != FFI_OK) {
        ffi_closure_free(c->closure);
        free(at);
        free(c);
        return 0;
    }
    return (int64_t)(intptr_t)c->code;
}

int32_t aot_ffi_call_qsort(int64_t fn, int64_t base, int32_t n, int32_t width, int64_t cmp)
{
    if (fn == 0 || cmp == 0) {
        return 0;
    }
    void (*f)(void *, size_t, size_t, void *) = (void (*)(void *, size_t, size_t, void *))(intptr_t)fn;
    f((void *)(intptr_t)base, (size_t)n, (size_t)width, (void *)(intptr_t)cmp);
    return n;
}
