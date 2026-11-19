#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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
static gc_frame *frames;
static uintptr_t *mark_stack;
static size_t mark_top;
static size_t mark_cap;
static size_t live_bytes;
static size_t threshold;
static int collecting;

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

static void mark_roots(void)
{
    for (gc_frame *f = frames; f; f = f->prev) {
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
    if (collecting) {
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
    if (!heap_start) {
        gc_init(256 * 1024 * 1024);
    }
    if (size < 0) {
        return 0;
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
    if (!h) {
        gc_collect();
        h = take_from_free(need);
        if (!h && (size_t)(heap_end - bump) >= need) {
            h = (gc_header *)bump;
            bump += need;
            h->size_flags = need;
            h->magic = GC_MAGIC;
            live_bytes += need;
        }
        if (!h) {
            return 0;
        }
    } else if (live_bytes > threshold) {
        gc_collect();
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
    frame->prev = frames;
    frames = frame;
}

void gc_frame_pop(gc_frame *frame)
{
    if (frames == frame) {
        frames = frame->prev;
    }
}

int64_t gc_live_bytes(void)
{
    return (int64_t)live_bytes;
}

int64_t gc_heap_size(void)
{
    return (int64_t)(heap_end - heap_start);
}