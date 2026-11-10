.class public final synthetic Lw0/t2;
.super Ljava/lang/Object;
.source "SourceFile"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field public final synthetic a:I

.field public final synthetic b:I

.field public final synthetic c:Ljava/lang/Object;

.field public final synthetic d:Ljava/lang/Object;

.field public final synthetic e:Ljava/lang/Object;


# direct methods
.method public synthetic constructor <init>(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;I)V
    .registers 6

    iput p5, p0, Lw0/t2;->a:I

    iput-object p1, p0, Lw0/t2;->c:Ljava/lang/Object;

    iput p2, p0, Lw0/t2;->b:I

    iput-object p3, p0, Lw0/t2;->d:Ljava/lang/Object;

    iput-object p4, p0, Lw0/t2;->e:Ljava/lang/Object;

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public final run()V
    .registers 16

    iget v0, p0, Lw0/t2;->a:I

    const/4 v1, 0x0

    const/4 v2, 0x1

    packed-switch v0, :pswitch_data_1a0

    iget-object v0, p0, Lw0/t2;->c:Ljava/lang/Object;

    check-cast v0, Landroid/car/hardware/property/CarPropertyManager;

    iget v3, p0, Lw0/t2;->b:I

    iget-object v4, p0, Lw0/t2;->d:Ljava/lang/Object;

    check-cast v4, Lcom/ex/auto/MainActivity;

    iget-object v5, p0, Lw0/t2;->e:Ljava/lang/Object;

    check-cast v5, Lw0/c9;

    sget v6, Lcom/ex/auto/MainActivity;->z1:I

    new-instance v6, Li2/j;

    invoke-direct {v6}, Ljava/lang/Object;-><init>()V

    sget-object v7, Lw0/u;->c:[I

    array-length v8, v7

    :goto_1f
    if-ge v1, v8, :cond_49

    aget v9, v7, v1

    sget v10, Lw0/u;->b:I

    invoke-static {v0, v10, v9, v3}, Lw0/g1;->l(Landroid/car/hardware/property/CarPropertyManager;III)Z

    move-result v10

    if-eqz v10, :cond_2e

    iput-boolean v2, v6, Li2/j;->a:Z

    goto :goto_49

    :cond_2e
    new-instance v10, Ljava/lang/StringBuilder;

    const-string v11, "AVAS sound type write area="

    invoke-direct {v10, v11}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v10, v9}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v9, " falhou"

    invoke-virtual {v10, v9}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v10}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v9

    const-string v10, "MainActivity"

    invoke-static {v10, v9}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    add-int/lit8 v1, v1, 0x1

    goto :goto_1f

    :cond_49
    :goto_49
    iget-boolean v0, v6, Li2/j;->a:Z

    if-eqz v0, :cond_5e

    invoke-virtual {v4}, Lcom/ex/auto/MainActivity;->o0()Landroid/content/SharedPreferences;

    move-result-object v0

    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v0

    const-string v1, "avas_sound_type_saved"

    invoke-interface {v0, v1, v3}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;

    move-result-object v0

    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V

    :cond_5e
    iget-object v0, v4, Lcom/ex/auto/MainActivity;->v:Landroid/os/Handler;

    new-instance v1, La1/g;

    const/16 v2, 0x13

    invoke-direct {v1, v4, v5, v6, v2}, La1/g;-><init>(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V

    invoke-virtual {v0, v1}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z

    return-void

    :pswitch_6b
    iget-object v0, p0, Lw0/t2;->c:Ljava/lang/Object;

    check-cast v0, Lcom/ex/auto/CentralWatchdogService;

    iget v3, p0, Lw0/t2;->b:I

    iget-object v4, p0, Lw0/t2;->d:Ljava/lang/Object;

    check-cast v4, Ljava/lang/String;

    iget-object v5, p0, Lw0/t2;->e:Ljava/lang/Object;

    check-cast v5, Lw0/r2;

    sget-object v6, Lcom/ex/auto/CentralWatchdogService;->R2:[I

    sget-boolean v6, Lcom/ex/auto/CentralWatchdogService;->T2:Z

    if-nez v6, :cond_81

    goto/16 :goto_19f

    :cond_81
    iget-object v6, v0, Lcom/ex/auto/CentralWatchdogService;->e0:Ljava/util/concurrent/atomic/AtomicInteger;

    invoke-virtual {v6}, Ljava/util/concurrent/atomic/AtomicInteger;->get()I

    move-result v6

    const-wide/16 v7, 0x1770

    if-eq v6, v3, :cond_a1

    iget-object v1, v0, Lcom/ex/auto/CentralWatchdogService;->a:Landroid/os/Handler;

    iget-object v2, v0, Lcom/ex/auto/CentralWatchdogService;->L2:Lw0/r2;

    invoke-virtual {v1, v2}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V

    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->t()Z

    move-result v1

    if-eqz v1, :cond_19f

    iget-object v1, v0, Lcom/ex/auto/CentralWatchdogService;->a:Landroid/os/Handler;

    iget-object v0, v0, Lcom/ex/auto/CentralWatchdogService;->L2:Lw0/r2;

    invoke-virtual {v1, v0, v7, v8}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z

    goto/16 :goto_19f

    :cond_a1
    iget-object v3, v0, Lcom/ex/auto/CentralWatchdogService;->b0:Ljava/lang/String;

    if-eqz v3, :cond_a7

    move v3, v2

    goto :goto_a8

    :cond_a7
    move v3, v1

    :goto_a8
    const/4 v6, 0x3

    const-string v9, "auto_proxy_enabled"

    if-eqz v4, :cond_165

    iput v1, v0, Lcom/ex/auto/CentralWatchdogService;->m0:I

    iget-object v3, v0, Lcom/ex/auto/CentralWatchdogService;->b0:Ljava/lang/String;

    invoke-virtual {v4, v3}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v3

    iput-object v4, v0, Lcom/ex/auto/CentralWatchdogService;->b0:Ljava/lang/String;

    const-string v10, "auto_route_on_aa_connect"

    if-nez v3, :cond_100

    sget-object v3, Lw0/ud;->a:Lw0/ud;

    const-string v3, "AutoRota: celular detectado em ap0 ip="

    const-string v11, " rotas="

    invoke-static {v3, v4, v11}, Landroidx/activity/result/b;->l(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v3

    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->W()Landroid/content/SharedPreferences;

    move-result-object v11

    invoke-interface {v11, v10, v1}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result v11

    invoke-virtual {v3, v11}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    const-string v11, " proxy="

    invoke-virtual {v3, v11}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->W()Landroid/content/SharedPreferences;

    move-result-object v11

    invoke-interface {v11, v9, v1}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result v11

    invoke-virtual {v3, v11}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v3

    const-string v11, "CentralWatchdog"

    invoke-static {v11, v3, v1}, Lw0/ud;->b(Ljava/lang/String;Ljava/lang/String;Z)V

    iget-object v3, v0, Lcom/ex/auto/CentralWatchdogService;->e0:Ljava/util/concurrent/atomic/AtomicInteger;

    invoke-virtual {v3}, Ljava/util/concurrent/atomic/AtomicInteger;->incrementAndGet()I

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v11

    const-wide/16 v13, 0x1388

    add-long/2addr v11, v13

    iput-wide v11, v0, Lcom/ex/auto/CentralWatchdogService;->g0:J

    iput-wide v13, v0, Lcom/ex/auto/CentralWatchdogService;->j0:J

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v11

    add-long/2addr v11, v13

    iput-wide v11, v0, Lcom/ex/auto/CentralWatchdogService;->k0:J

    :cond_100
    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->W()Landroid/content/SharedPreferences;

    move-result-object v3

    invoke-interface {v3, v10, v1}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result v3

    if-eqz v3, :cond_117

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v10

    iget-wide v12, v0, Lcom/ex/auto/CentralWatchdogService;->g0:J

    cmp-long v3, v10, v12

    if-ltz v3, :cond_117

    invoke-static {v0, v4}, Lcom/ex/auto/CentralWatchdogService;->h(Lcom/ex/auto/CentralWatchdogService;Ljava/lang/String;)V

    :cond_117
    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->W()Landroid/content/SharedPreferences;

    move-result-object v3

    invoke-interface {v3, v9, v1}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result v1

    if-eqz v1, :cond_14c

    iget-object v3, v0, Lcom/ex/auto/CentralWatchdogService;->i0:Ljava/lang/String;

    invoke-static {v3, v4}, Li2/g;->a(Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result v3

    if-nez v3, :cond_14c

    iget-boolean v3, v0, Lcom/ex/auto/CentralWatchdogService;->l0:Z

    if-nez v3, :cond_14c

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v9

    iget-wide v11, v0, Lcom/ex/auto/CentralWatchdogService;->k0:J

    cmp-long v3, v9, v11

    if-ltz v3, :cond_14c

    iput-boolean v2, v0, Lcom/ex/auto/CentralWatchdogService;->l0:Z

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v1

    iget-wide v9, v0, Lcom/ex/auto/CentralWatchdogService;->j0:J

    add-long/2addr v1, v9

    iput-wide v1, v0, Lcom/ex/auto/CentralWatchdogService;->k0:J

    new-instance v1, Lw0/o1;

    const/4 v2, 0x2

    invoke-direct {v1, v0, v4, v2}, Lw0/o1;-><init>(Lcom/ex/auto/CentralWatchdogService;Ljava/lang/String;I)V

    invoke-static {v1}, Lw0/f3;->a(Lh2/a;)V

    goto :goto_194

    :cond_14c
    if-eqz v1, :cond_194

    iget-object v1, v0, Lcom/ex/auto/CentralWatchdogService;->i0:Ljava/lang/String;

    invoke-static {v1, v4}, Li2/g;->a(Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_194

    iget-boolean v1, v0, Lcom/ex/auto/CentralWatchdogService;->n0:Z

    if-nez v1, :cond_194

    iput-boolean v2, v0, Lcom/ex/auto/CentralWatchdogService;->n0:Z

    new-instance v1, Lw0/o1;

    invoke-direct {v1, v0, v4, v6}, Lw0/o1;-><init>(Lcom/ex/auto/CentralWatchdogService;Ljava/lang/String;I)V

    invoke-static {v1}, Lw0/f3;->a(Lh2/a;)V

    goto :goto_194

    :cond_165
    iget v4, v0, Lcom/ex/auto/CentralWatchdogService;->m0:I

    add-int/2addr v4, v2

    iput v4, v0, Lcom/ex/auto/CentralWatchdogService;->m0:I

    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->W()Landroid/content/SharedPreferences;

    move-result-object v4

    invoke-interface {v4, v9, v1}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result v1

    iget v4, v0, Lcom/ex/auto/CentralWatchdogService;->m0:I

    if-lt v4, v6, :cond_194

    if-eqz v1, :cond_191

    iget-object v1, v0, Lcom/ex/auto/CentralWatchdogService;->i0:Ljava/lang/String;

    if-eqz v1, :cond_191

    iget-boolean v1, v0, Lcom/ex/auto/CentralWatchdogService;->l0:Z

    if-nez v1, :cond_191

    iput-boolean v2, v0, Lcom/ex/auto/CentralWatchdogService;->l0:Z

    iget-object v1, v0, Lcom/ex/auto/CentralWatchdogService;->i0:Ljava/lang/String;

    invoke-static {v1}, Li2/g;->b(Ljava/lang/Object;)V

    new-instance v2, Lw0/o1;

    const/4 v3, 0x4

    invoke-direct {v2, v0, v1, v3}, Lw0/o1;-><init>(Lcom/ex/auto/CentralWatchdogService;Ljava/lang/String;I)V

    invoke-static {v2}, Lw0/f3;->a(Lh2/a;)V

    goto :goto_194

    :cond_191
    invoke-static {v0, v3}, Lcom/ex/auto/CentralWatchdogService;->f(Lcom/ex/auto/CentralWatchdogService;Z)V

    :cond_194
    :goto_194
    invoke-virtual {v0}, Lcom/ex/auto/CentralWatchdogService;->t()Z

    move-result v1

    if-eqz v1, :cond_19f

    iget-object v0, v0, Lcom/ex/auto/CentralWatchdogService;->a:Landroid/os/Handler;

    invoke-virtual {v0, v5, v7, v8}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z

    :cond_19f
    :goto_19f
    return-void

    :pswitch_data_1a0
    .packed-switch 0x0
        :pswitch_6b
    .end packed-switch
.end method
