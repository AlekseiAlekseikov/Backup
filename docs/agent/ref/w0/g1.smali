.class public final Lw0/g1;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field public static final a:Ljava/util/concurrent/atomic/AtomicInteger;

.field public static final b:Ljava/util/concurrent/ThreadPoolExecutor;

.field public static final c:Ljava/util/concurrent/ThreadPoolExecutor;

.field public static final d:Ljava/util/concurrent/ConcurrentHashMap;


# direct methods
.method static constructor <clinit>()V
    .registers 10

    new-instance v0, Ljava/util/concurrent/atomic/AtomicInteger;

    invoke-direct {v0}, Ljava/util/concurrent/atomic/AtomicInteger;-><init>()V

    sput-object v0, Lw0/g1;->a:Ljava/util/concurrent/atomic/AtomicInteger;

    new-instance v1, Ljava/util/concurrent/ThreadPoolExecutor;

    sget-object v6, Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;

    new-instance v7, Ljava/util/concurrent/LinkedBlockingQueue;

    const/16 v0, 0xc

    invoke-direct {v7, v0}, Ljava/util/concurrent/LinkedBlockingQueue;-><init>(I)V

    new-instance v8, Lw0/c1;

    const/4 v0, 0x0

    invoke-direct {v8, v0}, Lw0/c1;-><init>(I)V

    const/4 v2, 0x6

    const/4 v3, 0x6

    const-wide/16 v4, 0x1e

    invoke-direct/range {v1 .. v8}, Ljava/util/concurrent/ThreadPoolExecutor;-><init>(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;Ljava/util/concurrent/ThreadFactory;)V

    const/4 v0, 0x1

    invoke-virtual {v1, v0}, Ljava/util/concurrent/ThreadPoolExecutor;->allowCoreThreadTimeOut(Z)V

    sput-object v1, Lw0/g1;->b:Ljava/util/concurrent/ThreadPoolExecutor;

    new-instance v2, Ljava/util/concurrent/ThreadPoolExecutor;

    new-instance v8, Ljava/util/concurrent/SynchronousQueue;

    invoke-direct {v8}, Ljava/util/concurrent/SynchronousQueue;-><init>()V

    new-instance v9, Lw0/c1;

    invoke-direct {v9, v0}, Lw0/c1;-><init>(I)V

    const/4 v3, 0x0

    const/4 v4, 0x4

    move-object v7, v6

    const-wide/16 v5, 0x1e

    invoke-direct/range {v2 .. v9}, Ljava/util/concurrent/ThreadPoolExecutor;-><init>(IIJLjava/util/concurrent/TimeUnit;Ljava/util/concurrent/BlockingQueue;Ljava/util/concurrent/ThreadFactory;)V

    sput-object v2, Lw0/g1;->c:Ljava/util/concurrent/ThreadPoolExecutor;

    new-instance v0, Ljava/util/concurrent/ConcurrentHashMap;

    invoke-direct {v0}, Ljava/util/concurrent/ConcurrentHashMap;-><init>()V

    sput-object v0, Lw0/g1;->d:Ljava/util/concurrent/ConcurrentHashMap;

    return-void
.end method

.method public static a(Landroid/car/hardware/property/CarPropertyManager;II)Ljava/lang/Boolean;
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "bool"

    invoke-static {p0, v0, p1, p2}, Lw0/g1;->f(Landroid/car/hardware/property/CarPropertyManager;Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/z0;

    const/4 v2, 0x4

    invoke-direct {v1, p0, p1, p2, v2}, Lw0/z0;-><init>(Landroid/car/hardware/property/CarPropertyManager;III)V

    invoke-static {v0, v1}, Lw0/g1;->g(Ljava/lang/String;Lh2/a;)Ljava/lang/Object;

    move-result-object p0

    check-cast p0, Ljava/lang/Boolean;

    return-object p0
.end method

.method public static b(Landroid/car/hardware/property/CarPropertyManager;II)Ljava/lang/Float;
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "float"

    invoke-static {p0, v0, p1, p2}, Lw0/g1;->f(Landroid/car/hardware/property/CarPropertyManager;Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/z0;

    const/4 v2, 0x1

    invoke-direct {v1, p0, p1, p2, v2}, Lw0/z0;-><init>(Landroid/car/hardware/property/CarPropertyManager;III)V

    invoke-static {v0, v1}, Lw0/g1;->g(Ljava/lang/String;Lh2/a;)Ljava/lang/Object;

    move-result-object p0

    check-cast p0, Ljava/lang/Float;

    return-object p0
.end method

.method public static c(Landroid/car/hardware/property/CarPropertyManager;II)Ljava/lang/Integer;
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "int"

    invoke-static {p0, v0, p1, p2}, Lw0/g1;->f(Landroid/car/hardware/property/CarPropertyManager;Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/z0;

    const/4 v2, 0x2

    invoke-direct {v1, p0, p1, p2, v2}, Lw0/z0;-><init>(Landroid/car/hardware/property/CarPropertyManager;III)V

    invoke-static {v0, v1}, Lw0/g1;->g(Ljava/lang/String;Lh2/a;)Ljava/lang/Object;

    move-result-object p0

    check-cast p0, Ljava/lang/Integer;

    return-object p0
.end method

.method public static d(Landroid/car/hardware/property/CarPropertyManager;II)Landroid/car/hardware/CarPropertyValue;
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-class v0, Ljava/lang/Integer;

    invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;

    move-result-object v0

    const-string v1, "raw:"

    invoke-virtual {v1, v0}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    invoke-static {p0, v0, p1, p2}, Lw0/g1;->f(Landroid/car/hardware/property/CarPropertyManager;Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/z0;

    const/4 v2, 0x0

    invoke-direct {v1, p0, p1, p2, v2}, Lw0/z0;-><init>(Landroid/car/hardware/property/CarPropertyManager;III)V

    invoke-static {v0, v1}, Lw0/g1;->g(Ljava/lang/String;Lh2/a;)Ljava/lang/Object;

    move-result-object p0

    check-cast p0, Landroid/car/hardware/CarPropertyValue;

    return-object p0
.end method

.method public static e(Landroid/car/hardware/property/CarPropertyManager;II)Ljava/lang/Object;
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-class v0, Ljava/lang/Integer;

    invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;

    move-result-object v0

    invoke-static {p0, v0, p1, p2}, Lw0/g1;->f(Landroid/car/hardware/property/CarPropertyManager;Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/z0;

    const/4 v2, 0x3

    invoke-direct {v1, p0, p1, p2, v2}, Lw0/z0;-><init>(Landroid/car/hardware/property/CarPropertyManager;III)V

    invoke-static {v0, v1}, Lw0/g1;->g(Ljava/lang/String;Lh2/a;)Ljava/lang/Object;

    move-result-object p0

    return-object p0
.end method

.method public static f(Landroid/car/hardware/property/CarPropertyManager;Ljava/lang/String;II)Ljava/lang/String;
    .registers 5

    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-static {p0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I

    move-result p0

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const/16 p0, 0x3a

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p3}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static g(Ljava/lang/String;Lh2/a;)Ljava/lang/Object;
    .registers 8

    sget-object v0, Lw0/g1;->d:Ljava/util/concurrent/ConcurrentHashMap;

    invoke-virtual {v0, p0}, Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v1

    check-cast v1, Ljava/util/concurrent/Future;

    const-string v2, "CarProps"

    const/4 v3, 0x0

    if-nez v1, :cond_35

    new-instance v1, Ljava/util/concurrent/FutureTask;

    new-instance v4, Lw0/a1;

    invoke-direct {v4, p1}, Lw0/a1;-><init>(Lh2/a;)V

    invoke-direct {v1, v4}, Ljava/util/concurrent/FutureTask;-><init>(Ljava/util/concurrent/Callable;)V

    invoke-virtual {v0, p0, v1}, Ljava/util/concurrent/ConcurrentHashMap;->putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object p1

    check-cast p1, Ljava/util/concurrent/Future;

    if-nez p1, :cond_34

    :try_start_1f
    sget-object p1, Lw0/g1;->b:Ljava/util/concurrent/ThreadPoolExecutor;

    new-instance v4, La1/i;

    const/4 v5, 0x6

    invoke-direct {v4, v1, v5, p0}, La1/i;-><init>(Ljava/lang/Object;ILjava/lang/Object;)V

    invoke-virtual {p1, v4}, Ljava/util/concurrent/ThreadPoolExecutor;->execute(Ljava/lang/Runnable;)V
    :try_end_2a
    .catchall {:try_start_1f .. :try_end_2a} :catchall_2b

    goto :goto_35

    :catchall_2b
    invoke-virtual {v0, p0, v1}, Ljava/util/concurrent/ConcurrentHashMap;->remove(Ljava/lang/Object;Ljava/lang/Object;)Z

    const-string p0, "leitura recusada: 6 threads presas e fila cheia"

    invoke-static {v2, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    return-object v3

    :cond_34
    move-object v1, p1

    :cond_35
    :goto_35
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide p0

    :try_start_39
    sget-object v0, Ljava/util/concurrent/TimeUnit;->MILLISECONDS:Ljava/util/concurrent/TimeUnit;

    const-wide/16 v4, 0x320

    invoke-interface {v1, v4, v5, v0}, Ljava/util/concurrent/Future;->get(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;

    move-result-object v0

    sget-object v1, Lw0/od;->d:Lw0/nd;

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v4

    sub-long/2addr v4, p0

    invoke-static {v1, v4, v5}, Lw0/od;->d(Lw0/nd;J)V
    :try_end_4b
    .catch Ljava/util/concurrent/TimeoutException; {:try_start_39 .. :try_end_4b} :catch_57
    .catchall {:try_start_39 .. :try_end_4b} :catchall_4c

    return-object v0

    :catchall_4c
    sget-object v0, Lw0/od;->d:Lw0/nd;

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v1

    sub-long/2addr v1, p0

    invoke-static {v0, v1, v2}, Lw0/od;->d(Lw0/nd;J)V

    goto :goto_69

    :catch_57
    const-string v0, "leitura estourou 800ms \u2014 devolvendo null (binder possivelmente preso)"

    invoke-static {v2, v0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    sget-object v0, Lw0/od;->a:Lw0/od;

    sget-object v1, Lw0/od;->d:Lw0/nd;

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v4

    sub-long/2addr v4, p0

    const/4 p0, 0x1

    invoke-virtual {v0, v1, v4, v5, p0}, Lw0/od;->c(Lw0/nd;JZ)V

    :goto_69
    return-object v3
.end method

.method public static final h(Landroid/car/hardware/property/CarPropertyManager;IZ)Z
    .registers 4

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const v0, 0x1520050a

    invoke-static {p0, v0, p1, p2}, Lw0/g1;->i(Landroid/car/hardware/property/CarPropertyManager;IIZ)Z

    move-result p0

    return p0
.end method

.method public static i(Landroid/car/hardware/property/CarPropertyManager;IIZ)Z
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "bool"

    invoke-static {v0, p1, p2}, Lw0/g1;->m(Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/y0;

    invoke-direct {v1, p0, p1, p2, p3}, Lw0/y0;-><init>(Landroid/car/hardware/property/CarPropertyManager;IIZ)V

    invoke-static {v0, v1}, Lw0/g1;->n(Ljava/lang/String;Lh2/a;)Z

    move-result p0

    return p0
.end method

.method public static j(Landroid/car/hardware/property/CarPropertyManager;IIF)Z
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "float"

    invoke-static {v0, p1, p2}, Lw0/g1;->m(Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/f1;

    invoke-direct {v1, p0, p1, p2, p3}, Lw0/f1;-><init>(Landroid/car/hardware/property/CarPropertyManager;IIF)V

    invoke-static {v0, v1}, Lw0/g1;->n(Ljava/lang/String;Lh2/a;)Z

    move-result p0

    return p0
.end method

.method public static final k(Landroid/car/hardware/property/CarPropertyManager;III)Z
    .registers 5

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-static {p0, p1, p2, p3}, Lw0/g1;->l(Landroid/car/hardware/property/CarPropertyManager;III)Z

    move-result p0

    return p0
.end method

.method public static l(Landroid/car/hardware/property/CarPropertyManager;III)Z
    .registers 6

    const-string v0, "cpm"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "int"

    invoke-static {v0, p1, p2}, Lw0/g1;->m(Ljava/lang/String;II)Ljava/lang/String;

    move-result-object v0

    new-instance v1, Lw0/d1;

    invoke-direct {v1, p0, p1, p2, p3}, Lw0/d1;-><init>(Landroid/car/hardware/property/CarPropertyManager;III)V

    invoke-static {v0, v1}, Lw0/g1;->n(Ljava/lang/String;Lh2/a;)Z

    move-result p0

    return p0
.end method

.method public static m(Ljava/lang/String;II)Ljava/lang/String;
    .registers 4

    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, " 0x"

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-static {p1}, Ljava/lang/Integer;->toHexString(I)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, " area=0x"

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-static {p2}, Ljava/lang/Integer;->toHexString(I)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static n(Ljava/lang/String;Lh2/a;)Z
    .registers 10

    const-string v0, "CarProps"

    const/4 v1, 0x0

    :try_start_3
    sget-object v2, Lw0/g1;->c:Ljava/util/concurrent/ThreadPoolExecutor;

    new-instance v3, Lw0/e1;

    const/4 v4, 0x0

    invoke-direct {v3, p1, v4}, Lw0/e1;-><init>(Lh2/a;I)V

    invoke-virtual {v2, v3}, Ljava/util/concurrent/AbstractExecutorService;->submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;

    move-result-object p1
    :try_end_f
    .catchall {:try_start_3 .. :try_end_f} :catchall_83

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v2

    const/4 v4, 0x1

    :try_start_14
    sget-object v5, Ljava/util/concurrent/TimeUnit;->MILLISECONDS:Ljava/util/concurrent/TimeUnit;

    const-wide/16 v6, 0x7d0

    invoke-interface {p1, v6, v7, v5}, Ljava/util/concurrent/Future;->get(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;

    sget-object p1, Lw0/od;->d:Lw0/nd;

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v5

    sub-long/2addr v5, v2

    invoke-static {p1, v5, v6}, Lw0/od;->d(Lw0/nd;J)V
    :try_end_25
    .catch Ljava/util/concurrent/TimeoutException; {:try_start_14 .. :try_end_25} :catch_60
    .catch Ljava/util/concurrent/ExecutionException; {:try_start_14 .. :try_end_25} :catch_26
    .catchall {:try_start_14 .. :try_end_25} :catchall_82

    return v4

    :catch_26
    move-exception p1

    const-string v4, "escrita falhou ("

    const-string v5, "): "

    invoke-static {v4, p0, v5}, Landroidx/activity/result/b;->l(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object p0

    invoke-virtual {p1}, Ljava/lang/Throwable;->getCause()Ljava/lang/Throwable;

    move-result-object v4

    if-eqz v4, :cond_3b

    invoke-virtual {v4}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object v4

    if-nez v4, :cond_4b

    :cond_3b
    invoke-virtual {p1}, Ljava/lang/Throwable;->getCause()Ljava/lang/Throwable;

    move-result-object p1

    if-eqz p1, :cond_4a

    invoke-virtual {p1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object p1

    invoke-virtual {p1}, Ljava/lang/Class;->getSimpleName()Ljava/lang/String;

    move-result-object v4

    goto :goto_4b

    :cond_4a
    const/4 v4, 0x0

    :cond_4b
    :goto_4b
    invoke-virtual {p0, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    sget-object p0, Lw0/od;->d:Lw0/nd;

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v4

    sub-long/2addr v4, v2

    invoke-static {p0, v4, v5}, Lw0/od;->d(Lw0/nd;J)V

    goto :goto_82

    :catch_60
    new-instance p1, Ljava/lang/StringBuilder;

    const-string v5, "escrita estourou 2000ms ("

    invoke-direct {p1, v5}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, ") \u2014 fila liberada, binder possivelmente preso"

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    sget-object p0, Lw0/od;->a:Lw0/od;

    sget-object p1, Lw0/od;->d:Lw0/nd;

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v5

    sub-long/2addr v5, v2

    invoke-virtual {p0, p1, v5, v6, v4}, Lw0/od;->c(Lw0/nd;JZ)V

    :catchall_82
    :goto_82
    return v1

    :catchall_83
    new-instance p1, Ljava/lang/StringBuilder;

    const-string v2, "escrita recusada ("

    invoke-direct {p1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, "): 4 escritores presos"

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    return v1
.end method
