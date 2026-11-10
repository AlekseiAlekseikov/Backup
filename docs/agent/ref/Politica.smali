.class public final Lcom/ex/auto/Politica;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field public static final a:Lcom/ex/auto/Politica;

.field public static final b:Z

.field public static volatile c:[I

.field public static volatile d:Z

.field public static final e:[Ljava/lang/String;

.field public static volatile f:Z


# direct methods
.method static constructor <clinit>()V
    .registers 3

    new-instance v0, Lcom/ex/auto/Politica;

    invoke-direct {v0}, Ljava/lang/Object;-><init>()V

    sput-object v0, Lcom/ex/auto/Politica;->a:Lcom/ex/auto/Politica;

    :try_start_7
    const-string v0, "expolitica"

    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    :try_end_c
    .catchall {:try_start_7 .. :try_end_c} :catchall_e

    const/4 v0, 0x1

    goto :goto_25

    :catchall_e
    move-exception v0

    new-instance v1, Ljava/lang/StringBuilder;

    const-string v2, "libexpolitica n\u00e3o carregou: "

    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    const/4 v0, 0x0

    :goto_25
    sput-boolean v0, Lcom/ex/auto/Politica;->b:Z

    const/16 v0, 0xd

    new-array v0, v0, [Ljava/lang/String;

    sput-object v0, Lcom/ex/auto/Politica;->e:[Ljava/lang/String;

    return-void
.end method

.method public static a(Ljava/lang/String;)V
    .registers 2

    :try_start_0
    const-string v0, "Politica"

    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_5
    .catchall {:try_start_0 .. :try_end_5} :catchall_5

    :catchall_5
    return-void
.end method

.method public static final b(I)Ljava/lang/String;
    .registers 6

    sget-boolean v0, Lcom/ex/auto/Politica;->b:Z

    const/4 v1, 0x0

    if-nez v0, :cond_6

    goto :goto_43

    :cond_6
    if-ltz p0, :cond_43

    sget-object v0, Lcom/ex/auto/Politica;->e:[Ljava/lang/String;

    array-length v2, v0

    if-lt p0, v2, :cond_e

    goto :goto_43

    :cond_e
    aget-object v2, v0, p0

    if-eqz v2, :cond_14

    move-object v1, v2

    goto :goto_43

    :cond_14
    :try_start_14
    invoke-static {p0}, Lcom/ex/auto/Politica;->e(I)[B

    move-result-object v2

    const/4 v3, 0x7

    invoke-static {v3, v2}, Lcom/ex/auto/Politica;->nativeCall(I[B)[B

    move-result-object v2

    array-length v3, v2

    if-nez v3, :cond_21

    goto :goto_43

    :cond_21
    new-instance v3, Ljava/lang/String;

    sget-object v4, Lo2/a;->a:Ljava/nio/charset/Charset;

    invoke-direct {v3, v2, v4}, Ljava/lang/String;-><init>([BLjava/nio/charset/Charset;)V

    aput-object v3, v0, p0
    :try_end_2a
    .catchall {:try_start_14 .. :try_end_2a} :catchall_2c

    move-object v1, v3

    goto :goto_43

    :catchall_2c
    move-exception v0

    const-string v2, "texto["

    const-string v3, "] indispon\u00edvel: "

    invoke-static {v2, p0, v3}, Landroidx/activity/result/b;->k(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object p0

    invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p0, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    :cond_43
    :goto_43
    if-nez v1, :cond_51

    sget-boolean p0, Lcom/ex/auto/Politica;->f:Z

    if-nez p0, :cond_51

    const/4 p0, 0x1

    sput-boolean p0, Lcom/ex/auto/Politica;->f:Z

    const-string p0, "cofre de caminhos indispon\u00edvel \u2014 binds e servi\u00e7os do init ficam desligados at\u00e9 a biblioteca carregar"

    invoke-static {p0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    :cond_51
    if-nez v1, :cond_55

    const-string v1, "/nao-disponivel"

    :cond_55
    return-object v1
.end method

.method public static d(IJ)Lw0/ne;
    .registers 11

    sget-boolean v0, Lcom/ex/auto/Politica;->b:Z

    const/4 v1, 0x0

    if-nez v0, :cond_6

    goto :goto_2d

    :cond_6
    const-wide/16 v2, 0x0

    cmp-long v0, p1, v2

    if-gez v0, :cond_e

    const/4 p1, -0x1

    goto :goto_17

    :cond_e
    const-wide/32 v2, 0x7fffffff

    cmp-long v0, p1, v2

    if-lez v0, :cond_16

    move-wide p1, v2

    :cond_16
    long-to-int p1, p1

    :goto_17
    :try_start_17
    invoke-static {p0}, Lcom/ex/auto/Politica;->e(I)[B

    move-result-object p0

    invoke-static {p1}, Lcom/ex/auto/Politica;->e(I)[B

    move-result-object p1

    invoke-static {p0, p1}, Ly1/f;->c0([B[B)[B

    move-result-object p0

    const/4 p1, 0x4

    invoke-static {p1, p0}, Lcom/ex/auto/Politica;->nativeCall(I[B)[B

    move-result-object p0

    array-length p2, p0

    const/16 v0, 0xc

    if-ge p2, v0, :cond_2e

    :goto_2d
    return-object v1

    :cond_2e
    new-instance v2, Lw0/ne;

    const/4 p2, 0x0

    invoke-static {p2, p0}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result v3

    invoke-static {p1, p0}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result p1

    int-to-long v4, p1

    const/16 p1, 0x8

    invoke-static {p1, p0}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result p0

    int-to-long v6, p0

    invoke-direct/range {v2 .. v7}, Lw0/ne;-><init>(IJJ)V
    :try_end_44
    .catchall {:try_start_17 .. :try_end_44} :catchall_45

    return-object v2

    :catchall_45
    move-exception v0

    move-object p0, v0

    new-instance p1, Ljava/lang/StringBuilder;

    const-string p2, "decis\u00e3o de sa\u00fade falhou: "

    invoke-direct {p1, p2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    return-object v1
.end method

.method public static e(I)[B
    .registers 6

    and-int/lit16 v0, p0, 0xff

    int-to-byte v0, v0

    shr-int/lit8 v1, p0, 0x8

    and-int/lit16 v1, v1, 0xff

    int-to-byte v1, v1

    shr-int/lit8 v2, p0, 0x10

    and-int/lit16 v2, v2, 0xff

    int-to-byte v2, v2

    shr-int/lit8 p0, p0, 0x18

    and-int/lit16 p0, p0, 0xff

    int-to-byte p0, p0

    const/4 v3, 0x4

    new-array v3, v3, [B

    const/4 v4, 0x0

    aput-byte v0, v3, v4

    const/4 v0, 0x1

    aput-byte v1, v3, v0

    const/4 v0, 0x2

    aput-byte v2, v3, v0

    const/4 v0, 0x3

    aput-byte p0, v3, v0

    return-object v3
.end method

.method public static f(I[B)I
    .registers 4

    aget-byte v0, p1, p0

    and-int/lit16 v0, v0, 0xff

    add-int/lit8 v1, p0, 0x1

    aget-byte v1, p1, v1

    and-int/lit16 v1, v1, 0xff

    shl-int/lit8 v1, v1, 0x8

    or-int/2addr v0, v1

    add-int/lit8 v1, p0, 0x2

    aget-byte v1, p1, v1

    and-int/lit16 v1, v1, 0xff

    shl-int/lit8 v1, v1, 0x10

    or-int/2addr v0, v1

    add-int/lit8 p0, p0, 0x3

    aget-byte p0, p1, p0

    and-int/lit16 p0, p0, 0xff

    shl-int/lit8 p0, p0, 0x18

    or-int/2addr p0, v0

    return p0
.end method

.method public static g(I)Ljava/util/ArrayList;
    .registers 9

    sget-boolean v0, Lcom/ex/auto/Politica;->b:Z

    const/4 v1, 0x0

    if-nez v0, :cond_6

    goto :goto_26

    :cond_6
    const/4 v0, 0x1

    :try_start_7
    invoke-static {v0}, Lcom/ex/auto/Politica;->e(I)[B

    move-result-object v2

    invoke-static {p0}, Lcom/ex/auto/Politica;->e(I)[B

    move-result-object p0

    invoke-static {v2, p0}, Ly1/f;->c0([B[B)[B

    move-result-object p0

    const/4 v2, 0x0

    invoke-static {v2}, Lcom/ex/auto/Politica;->e(I)[B

    move-result-object v3

    invoke-static {p0, v3}, Ly1/f;->c0([B[B)[B

    move-result-object p0

    invoke-static {v0, p0}, Lcom/ex/auto/Politica;->nativeCall(I[B)[B

    move-result-object p0

    invoke-static {v2, p0}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result v0

    if-gez v0, :cond_27

    :goto_26
    return-object v1

    :cond_27
    invoke-static {v2, v0}, Lk1/a;->T(II)Ll2/c;

    move-result-object v0

    new-instance v2, Ljava/util/ArrayList;

    invoke-static {v0}, Ly1/i;->X(Ljava/lang/Iterable;)I

    move-result v3

    invoke-direct {v2, v3}, Ljava/util/ArrayList;-><init>(I)V

    invoke-virtual {v0}, Ll2/a;->iterator()Ljava/util/Iterator;

    move-result-object v0

    :goto_38
    move-object v3, v0

    check-cast v3, Ll2/b;

    iget-boolean v3, v3, Ll2/b;->c:Z

    if-eqz v3, :cond_60

    move-object v3, v0

    check-cast v3, Ll2/b;

    invoke-virtual {v3}, Ll2/b;->nextInt()I

    move-result v3

    new-instance v4, Lw0/me;

    mul-int/lit8 v3, v3, 0x8

    add-int/lit8 v5, v3, 0x4

    invoke-static {v5, p0}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result v5

    add-int/lit8 v3, v3, 0x8

    invoke-static {v3, p0}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result v3

    int-to-long v6, v3

    invoke-direct {v4, v5, v6, v7}, Lw0/me;-><init>(IJ)V

    invoke-virtual {v2, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :try_end_5d
    .catchall {:try_start_7 .. :try_end_5d} :catchall_5e

    goto :goto_38

    :catchall_5e
    move-exception p0

    goto :goto_61

    :cond_60
    return-object v2

    :goto_61
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v2, "pol\u00edtica nativa falhou: "

    invoke-direct {v0, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    return-object v1
.end method

.method public static final h(I)I
    .registers 2

    sget-object v0, Lcom/ex/auto/Politica;->a:Lcom/ex/auto/Politica;

    invoke-virtual {v0, p0}, Lcom/ex/auto/Politica;->c(I)Ljava/lang/Integer;

    move-result-object p0

    if-nez p0, :cond_14

    sget-boolean v0, Lcom/ex/auto/Politica;->d:Z

    if-nez v0, :cond_14

    const/4 v0, 0x1

    sput-boolean v0, Lcom/ex/auto/Politica;->d:Z

    const-string v0, "cofre de properties indispon\u00edvel \u2014 as fun\u00e7\u00f5es do carro que dependem dele v\u00e3o se declarar sem dado at\u00e9 a biblioteca carregar"

    invoke-static {v0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    :cond_14
    if-eqz p0, :cond_1b

    invoke-virtual {p0}, Ljava/lang/Integer;->intValue()I

    move-result p0

    return p0

    :cond_1b
    const/4 p0, -0x1

    return p0
.end method

.method public static i()[B
    .registers 4

    sget-boolean v0, Lcom/ex/auto/Politica;->b:Z

    const/4 v1, 0x0

    if-nez v0, :cond_6

    goto :goto_14

    :cond_6
    const/4 v0, 0x0

    :try_start_7
    new-array v0, v0, [B

    const/4 v2, 0x2

    invoke-static {v2, v0}, Lcom/ex/auto/Politica;->nativeCall(I[B)[B

    move-result-object v0

    array-length v2, v0
    :try_end_f
    .catchall {:try_start_7 .. :try_end_f} :catchall_15

    const/16 v3, 0x20

    if-ne v2, v3, :cond_14

    return-object v0

    :cond_14
    :goto_14
    return-object v1

    :catchall_15
    move-exception v0

    new-instance v2, Ljava/lang/StringBuilder;

    const-string v3, "semente indispon\u00edvel: "

    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    return-object v1
.end method

.method public static j()Ljava/lang/String;
    .registers 8

    const-string v0, "substring(...)"

    sget-boolean v1, Lcom/ex/auto/Politica;->b:Z

    const/4 v2, 0x0

    if-nez v1, :cond_8

    goto :goto_15

    :cond_8
    const/4 v1, 0x0

    :try_start_9
    new-array v3, v1, [B

    const/4 v4, 0x6

    invoke-static {v4, v3}, Lcom/ex/auto/Politica;->nativeCall(I[B)[B

    move-result-object v3

    array-length v4, v3

    const/16 v5, 0x10

    if-eq v4, v5, :cond_16

    :goto_15
    return-object v2

    :cond_16
    new-instance v4, Lw0/sa;

    const/16 v6, 0x18

    invoke-direct {v4, v6}, Lw0/sa;-><init>(I)V

    invoke-static {v3, v4}, Ly1/f;->a0([BLh2/l;)Ljava/lang/String;

    move-result-object v3

    new-instance v4, Ljava/lang/StringBuilder;

    invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V

    const/16 v6, 0x8

    invoke-virtual {v3, v1, v6}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v1

    invoke-static {v1, v0}, Li2/g;->d(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const/16 v1, 0x2d

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    const/16 v7, 0xc

    invoke-virtual {v3, v6, v7}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v6

    invoke-static {v6, v0}, Li2/g;->d(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {v4, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v3, v7, v5}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v6

    invoke-static {v6, v0}, Li2/g;->d(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {v4, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    const/16 v6, 0x14

    invoke-virtual {v3, v5, v6}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v5

    invoke-static {v5, v0}, Li2/g;->d(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    const/16 v1, 0x20

    invoke-virtual {v3, v6, v1}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v1

    invoke-static {v1, v0}, Li2/g;->d(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0
    :try_end_72
    .catchall {:try_start_9 .. :try_end_72} :catchall_73

    return-object v0

    :catchall_73
    move-exception v0

    new-instance v1, Ljava/lang/StringBuilder;

    const-string v3, "uuid indispon\u00edvel: "

    invoke-direct {v1, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V

    return-object v2
.end method

.method private static final native nativeCall(I[B)[B
.end method


# virtual methods
.method public final c(I)Ljava/lang/Integer;
    .registers 8

    sget-boolean v0, Lcom/ex/auto/Politica;->b:Z

    const/4 v1, 0x0

    if-nez v0, :cond_6

    return-object v1

    :cond_6
    sget-object v0, Lcom/ex/auto/Politica;->c:[I

    if-eqz v0, :cond_f

    invoke-static {v0, p1}, Ly1/f;->Y([II)Ljava/lang/Integer;

    move-result-object p1

    return-object p1

    :cond_f
    monitor-enter p0

    :try_start_10
    sget-object v0, Lcom/ex/auto/Politica;->c:[I

    if-eqz v0, :cond_1c

    invoke-static {v0, p1}, Ly1/f;->Y([II)Ljava/lang/Integer;

    move-result-object p1
    :try_end_18
    .catchall {:try_start_10 .. :try_end_18} :catchall_1a

    monitor-exit p0

    return-object p1

    :catchall_1a
    move-exception p1

    goto :goto_69

    :cond_1c
    const/4 v0, 0x0

    :try_start_1d
    new-array v2, v0, [B

    const/4 v3, 0x5

    invoke-static {v3, v2}, Lcom/ex/auto/Politica;->nativeCall(I[B)[B

    move-result-object v2

    invoke-static {v0, v2}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result v3

    if-lez v3, :cond_5c

    array-length v4, v2

    mul-int/lit8 v5, v3, 0x4

    add-int/lit8 v5, v5, 0x4

    if-ge v4, v5, :cond_32

    goto :goto_5c

    :cond_32
    new-array v4, v3, [I

    :goto_34
    if-ge v0, v3, :cond_5d

    mul-int/lit8 v5, v0, 0x4

    add-int/lit8 v5, v5, 0x4

    invoke-static {v5, v2}, Lcom/ex/auto/Politica;->f(I[B)I

    move-result v5

    aput v5, v4, v0
    :try_end_40
    .catchall {:try_start_1d .. :try_end_40} :catchall_43

    add-int/lit8 v0, v0, 0x1

    goto :goto_34

    :catchall_43
    move-exception v0

    :try_start_44
    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    const-string v3, "cofre indispon\u00edvel: "

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/ex/auto/Politica;->a(Ljava/lang/String;)V
    :try_end_5c
    .catchall {:try_start_44 .. :try_end_5c} :catchall_1a

    :cond_5c
    :goto_5c
    move-object v4, v1

    :cond_5d
    if-nez v4, :cond_61

    monitor-exit p0

    return-object v1

    :cond_61
    :try_start_61
    sput-object v4, Lcom/ex/auto/Politica;->c:[I

    invoke-static {v4, p1}, Ly1/f;->Y([II)Ljava/lang/Integer;

    move-result-object p1
    :try_end_67
    .catchall {:try_start_61 .. :try_end_67} :catchall_1a

    monitor-exit p0

    return-object p1

    :goto_69
    monitor-exit p0

    throw p1
.end method
