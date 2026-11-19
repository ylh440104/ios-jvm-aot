declare ptr @gc_calloc(i64, i64)
declare i64 @gc_live_bytes()
declare void @gc_collect()
declare i32 @printf(ptr, ...)
@gc_roots = global ptr null
@gc_root_count = global i64 0
@.fa = private unnamed_addr constant [10 x i8] c"live=%ld\0A\00"
@.fb = private unnamed_addr constant [11 x i8] c"after=%ld\0A\00"
define i32 @main(i32 %a, ptr %b) {
entry:
  %i0 = call i64 @gc_live_bytes()
  %p1 = call i32 (ptr, ...) @printf(ptr @.fa, i64 %i0)
  %x1 = call ptr @gc_calloc(i64 1024, i64 1)
  %x2 = call ptr @gc_calloc(i64 1024, i64 1)
  %x3 = call ptr @gc_calloc(i64 1024, i64 1)
  %i2 = call i64 @gc_live_bytes()
  %p2 = call i32 (ptr, ...) @printf(ptr @.fb, i64 %i2)
  call void @gc_collect()
  %i3 = call i64 @gc_live_bytes()
  %p3 = call i32 (ptr, ...) @printf(ptr @.fb, i64 %i3)
  ret i32 0
}