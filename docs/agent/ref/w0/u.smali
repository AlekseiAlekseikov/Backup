.class public abstract Lw0/u;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field public static final A:Ljava/lang/Object;

.field public static final B:[I

.field public static final a:I

.field public static final b:I

.field public static final c:[I

.field public static final d:I

.field public static final e:I

.field public static final f:I

.field public static final g:Ljava/lang/Object;

.field public static final h:Ljava/util/Set;

.field public static final i:Ljava/lang/String;

.field public static final j:I

.field public static final k:I

.field public static final l:I

.field public static final m:I

.field public static final n:I

.field public static final o:I

.field public static final p:I

.field public static final q:I

.field public static final r:I

.field public static final s:[I

.field public static final t:Ljava/lang/Object;

.field public static final u:Ljava/lang/Object;

.field public static final v:Ljava/lang/String;

.field public static final w:Ljava/lang/String;

.field public static final x:Ljava/util/List;

.field public static final y:Ljava/lang/String;

.field public static final z:Ljava/lang/String;


# direct methods
.method static constructor <clinit>()V
    .registers 16

    const-string v0, "com.wstxda.viper4android"

    invoke-static {v0}, Lk1/a;->I(Ljava/lang/Object;)Ljava/util/List;

    const/4 v0, 0x6

    invoke-static {v0}, Lcom/ex/auto/Politica;->h(I)I

    move-result v0

    sput v0, Lw0/u;->a:I

    const/4 v0, 0x7

    invoke-static {v0}, Lcom/ex/auto/Politica;->h(I)I

    move-result v0

    sput v0, Lw0/u;->b:I

    const/4 v0, 0x0

    const/4 v1, 0x1

    filled-new-array {v0, v1}, [I

    move-result-object v2

    sput-object v2, Lw0/u;->c:[I

    const/16 v2, 0x21

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->d:I

    const/16 v2, 0x8

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->e:I

    const/16 v2, 0x9

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->f:I

    new-instance v2, Lx1/a;

    const-string v3, "com.njda.adapter.aa"

    const-string v4, "com.njda.adapter"

    invoke-direct {v2, v3, v4}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v5, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-aa.json"

    invoke-direct {v5, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.njda.adapter.aa1114"

    invoke-direct {v2, v3, v4}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v6, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-aa-1114.json"

    invoke-direct {v6, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.njda.adapter.cp"

    invoke-direct {v2, v3, v4}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v7, Lx1/a;

    const-string v8, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-1111.json"

    invoke-direct {v7, v8, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    invoke-direct {v2, v3, v4}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v8, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor.json"

    invoke-direct {v8, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.njda.adapter.cp1114"

    invoke-direct {v2, v3, v4}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v9, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-1114.json"

    invoke-direct {v9, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.geely.connadaptorlogger"

    invoke-direct {v2, v3, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v10, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptorlogger.json"

    invoke-direct {v10, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.flyme.auto.keyserver"

    invoke-direct {v2, v3, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v11, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/geelyautokeyserver-patch.json"

    invoke-direct {v11, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.flyme.auto.systemuiplugin"

    invoke-direct {v2, v3, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v12, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/Outros/main/hvacdigital.json"

    invoke-direct {v12, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.flyme.auto.launcher"

    invoke-direct {v2, v3, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v13, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/Outros/main/launchergesture.json"

    invoke-direct {v13, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "br.com.central.dualdashcam"

    invoke-direct {v2, v3, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v14, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/Outros/main/dualdashcam.json"

    invoke-direct {v14, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v2, Lx1/a;

    const-string v3, "com.geely.eisapo"

    invoke-direct {v2, v3, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v15, Lx1/a;

    const-string v3, "https://raw.githubusercontent.com/swimapps/Outros/main/eisapo.json"

    invoke-direct {v15, v3, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    filled-new-array/range {v5 .. v15}, [Lx1/a;

    move-result-object v2

    invoke-static {v2}, Ly1/s;->X([Lx1/a;)Ljava/util/Map;

    move-result-object v2

    sput-object v2, Lw0/u;->g:Ljava/lang/Object;

    const-string v14, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/GeelyAutoSystemUIPlugin-digital.apk"

    const-string v15, "https://raw.githubusercontent.com/swimapps/Outros/main/GeelyAutoLauncher-gesture-signed.apk"

    const-string v3, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-aa.json"

    const-string v4, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-aa-1114.json"

    const-string v5, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-1111.json"

    const-string v6, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor.json"

    const-string v7, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-1114.json"

    const-string v8, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptorlogger.json"

    const-string v9, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/geelyautokeyserver-patch.json"

    const-string v10, "https://raw.githubusercontent.com/swimapps/Outros/main/hvacdigital.json"

    const-string v11, "https://raw.githubusercontent.com/swimapps/Outros/main/launchergesture.json"

    const-string v12, "https://raw.githubusercontent.com/swimapps/Outros/main/dualdashcam.json"

    const-string v13, "https://raw.githubusercontent.com/swimapps/Outros/main/eisapo.json"

    filled-new-array/range {v3 .. v15}, [Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Lk1/a;->P([Ljava/lang/Object;)Ljava/util/Set;

    move-result-object v2

    sput-object v2, Lw0/u;->h:Ljava/util/Set;

    invoke-static {v0}, Lcom/ex/auto/Politica;->b(I)Ljava/lang/String;

    move-result-object v2

    sput-object v2, Lw0/u;->i:Ljava/lang/String;

    const/16 v2, 0xa

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->j:I

    const/16 v2, 0xb

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->k:I

    const/16 v2, 0xc

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->l:I

    const/16 v2, 0xd

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->m:I

    const/16 v2, 0xe

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->n:I

    const/16 v2, 0xf

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v2

    sput v2, Lw0/u;->o:I

    const/16 v2, 0x10

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v3

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    move-result v4

    sput v4, Lw0/u;->p:I

    const/16 v4, 0x11

    invoke-static {v4}, Lcom/ex/auto/Politica;->h(I)I

    move-result v4

    sput v4, Lw0/u;->q:I

    const/16 v4, 0x12

    invoke-static {v4}, Lcom/ex/auto/Politica;->h(I)I

    move-result v4

    sput v4, Lw0/u;->r:I

    const/16 v4, 0x40

    invoke-static {v4}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v5

    const/16 v6, 0x100

    invoke-static {v6}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v7

    const/16 v8, 0x400

    invoke-static {v8}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v9

    filled-new-array {v2, v4, v6, v8}, [I

    move-result-object v2

    sput-object v2, Lw0/u;->s:[I

    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    new-instance v4, Lx1/a;

    invoke-direct {v4, v2, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    const/4 v2, 0x4

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v6

    new-instance v8, Lx1/a;

    invoke-direct {v8, v6, v5}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v6, Lx1/a;

    invoke-direct {v6, v3, v7}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v10, Lx1/a;

    invoke-direct {v10, v5, v9}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    filled-new-array {v4, v8, v6, v10}, [Lx1/a;

    move-result-object v4

    invoke-static {v4}, Ly1/s;->X([Lx1/a;)Ljava/util/Map;

    move-result-object v4

    sput-object v4, Lw0/u;->t:Ljava/lang/Object;

    new-instance v4, Lx1/a;

    invoke-direct {v4, v3, v5}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v6, Lx1/a;

    invoke-direct {v6, v5, v3}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v3, Lx1/a;

    invoke-direct {v3, v7, v9}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    new-instance v5, Lx1/a;

    invoke-direct {v5, v9, v7}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    filled-new-array {v4, v6, v3, v5}, [Lx1/a;

    move-result-object v3

    invoke-static {v3}, Ly1/s;->X([Lx1/a;)Ljava/util/Map;

    move-result-object v3

    sput-object v3, Lw0/u;->u:Ljava/lang/Object;

    const/4 v3, 0x2

    invoke-static {v3}, Lcom/ex/auto/Politica;->b(I)Ljava/lang/String;

    move-result-object v3

    sput-object v3, Lw0/u;->v:Ljava/lang/String;

    const/4 v3, 0x3

    invoke-static {v3}, Lcom/ex/auto/Politica;->b(I)Ljava/lang/String;

    move-result-object v3

    sput-object v3, Lw0/u;->w:Ljava/lang/String;

    new-instance v3, Lw0/t;

    const v4, 0x1c7aa

    const-string v5, "2c019dfb1d38ee6e5c3b20a876ae73fb"

    const-string v6, "1111"

    const-string v7, "c474b4bb1a2244d5bd1d3119d6fb0bfc"

    invoke-direct {v3, v6, v7, v4, v5}, Lw0/t;-><init>(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V

    new-instance v4, Lw0/t;

    const v5, 0x1c7a1

    const-string v6, "3c1c4d714d1810373b0b0728b68ec648"

    const-string v7, "1114"

    const-string v8, "de34e468ad334b7062038c5de46f1c52"

    invoke-direct {v4, v7, v8, v5, v6}, Lw0/t;-><init>(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V

    filled-new-array {v3, v4}, [Lw0/t;

    move-result-object v3

    invoke-static {v3}, Ly1/f;->U([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v3

    sput-object v3, Lw0/u;->x:Ljava/util/List;

    const/4 v3, 0x5

    invoke-static {v3}, Lcom/ex/auto/Politica;->b(I)Ljava/lang/String;

    move-result-object v4

    sput-object v4, Lw0/u;->y:Ljava/lang/String;

    invoke-static {v2}, Lcom/ex/auto/Politica;->b(I)Ljava/lang/String;

    move-result-object v2

    sput-object v2, Lw0/u;->z:Ljava/lang/String;

    const-string v2, "TBox"

    invoke-static {v2}, Lk1/a;->I(Ljava/lang/Object;)Ljava/util/List;

    move-result-object v2

    new-instance v4, Lx1/a;

    const-string v5, "silence_tbox_log"

    invoke-direct {v4, v5, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    const-string v2, "ArcSoftAVM_Eng"

    const-string v5, "ArcSoftAVM_sdk"

    filled-new-array {v2, v5}, [Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Ly1/f;->U([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v2

    new-instance v5, Lx1/a;

    const-string v6, "silence_avm_log"

    invoke-direct {v5, v6, v2}, Lx1/a;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V

    filled-new-array {v4, v5}, [Lx1/a;

    move-result-object v2

    invoke-static {v2}, Ly1/s;->X([Lx1/a;)Ljava/util/Map;

    move-result-object v2

    sput-object v2, Lw0/u;->A:Ljava/lang/Object;

    const/16 v2, 0x13

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    const/16 v2, 0x22

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    const/16 v2, 0x23

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    const/16 v2, 0x24

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    const/16 v2, 0x25

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    const/16 v2, 0x26

    invoke-static {v2}, Lcom/ex/auto/Politica;->h(I)I

    const/16 v2, 0x31

    filled-new-array {v1, v3, v2, v0}, [I

    move-result-object v0

    sput-object v0, Lw0/u;->B:[I

    return-void
.end method

.method public static a(Landroid/content/SharedPreferences;)Z
    .registers 3

    const-string v0, "connadaptor_modo"

    const-string v1, "ambos"

    invoke-interface {p0, v0, v1}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    if-nez p0, :cond_b

    move-object p0, v1

    :cond_b
    const-string v0, "aa"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-nez v0, :cond_1c

    invoke-virtual {p0, v1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_1a

    goto :goto_1c

    :cond_1a
    const/4 p0, 0x0

    return p0

    :cond_1c
    :goto_1c
    const/4 p0, 0x1

    return p0
.end method

.method public static b(Landroid/content/SharedPreferences;)Z
    .registers 3

    const-string v0, "connadaptor_modo"

    const-string v1, "ambos"

    invoke-interface {p0, v0, v1}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    if-nez p0, :cond_b

    goto :goto_c

    :cond_b
    move-object v1, p0

    :goto_c
    invoke-static {v1}, Lw0/u;->j(Ljava/lang/String;)Z

    move-result p0

    return p0
.end method

.method public static final c(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .registers 4

    const-string v0, "modo"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "versao"

    invoke-static {p1, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "/data/local/tmp/fixes/ca_fix_"

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const/16 p1, 0x5f

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, ".apk"

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static final d(Landroid/content/SharedPreferences;)Ljava/lang/String;
    .registers 5

    const-string v0, "connadaptor_modo"

    const-string v1, "ambos"

    invoke-interface {p0, v0, v1}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-nez v0, :cond_b

    goto :goto_c

    :cond_b
    move-object v1, v0

    :goto_c
    const-string v0, "connadaptor_versao_alvo"

    const/4 v2, 0x0

    invoke-interface {p0, v0, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :cond_1f

    invoke-static {v0}, Lo2/k;->m0(Ljava/lang/CharSequence;)Z

    move-result v3

    if-nez v3, :cond_1c

    goto :goto_1d

    :cond_1c
    move-object v0, v2

    :goto_1d
    if-nez v0, :cond_34

    :cond_1f
    const-string v0, "connadaptor_arquivo_versao"

    invoke-interface {p0, v0, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    if-eqz p0, :cond_2e

    invoke-static {p0}, Lo2/k;->m0(Ljava/lang/CharSequence;)Z

    move-result v0

    if-nez v0, :cond_2e

    move-object v2, p0

    :cond_2e
    if-nez v2, :cond_33

    const-string v0, "1111"

    goto :goto_34

    :cond_33
    move-object v0, v2

    :cond_34
    :goto_34
    invoke-static {v1, v0}, Lw0/u;->c(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static final e(Ljava/lang/String;)Ljava/lang/String;
    .registers 3

    const/4 v0, 0x0

    if-eqz p0, :cond_1f

    invoke-static {p0}, Lo2/k;->E0(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;

    move-result-object p0

    invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;

    move-result-object p0

    if-eqz p0, :cond_1f

    sget-object v1, Ljava/util/Locale;->ROOT:Ljava/util/Locale;

    invoke-virtual {p0, v1}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;

    move-result-object p0

    const-string v1, "toLowerCase(...)"

    invoke-static {p0, v1}, Li2/g;->d(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-interface {p0}, Ljava/lang/CharSequence;->length()I

    move-result v1

    if-lez v1, :cond_1f

    return-object p0

    :cond_1f
    return-object v0
.end method

.method public static f(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;
    .registers 4

    const-string v0, "versao"

    invoke-static {p1, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "aa"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v0

    const-string v1, "1114"

    if-eqz v0, :cond_23

    invoke-virtual {p1, v1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_1c

    const-string p0, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-aa-1114.json"

    invoke-static {p0}, Lk1/a;->I(Ljava/lang/Object;)Ljava/util/List;

    move-result-object p0

    return-object p0

    :cond_1c
    const-string p0, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-aa.json"

    invoke-static {p0}, Lk1/a;->I(Ljava/lang/Object;)Ljava/util/List;

    move-result-object p0

    return-object p0

    :cond_23
    const-string v0, "carplay"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_32

    const-string p0, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-carplay.json"

    invoke-static {p0}, Lk1/a;->I(Ljava/lang/Object;)Ljava/util/List;

    move-result-object p0

    return-object p0

    :cond_32
    invoke-virtual {p1, v1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_3f

    const-string p0, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-1114.json"

    invoke-static {p0}, Lk1/a;->I(Ljava/lang/Object;)Ljava/util/List;

    move-result-object p0

    return-object p0

    :cond_3f
    const-string p0, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor-1111.json"

    const-string p1, "https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/connadaptor.json"

    filled-new-array {p0, p1}, [Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Ly1/f;->U([Ljava/lang/Object;)Ljava/util/List;

    move-result-object p0

    return-object p0
.end method

.method public static g(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .registers 4

    const-string v0, "modo"

    invoke-static {p0, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "versao"

    invoke-static {p1, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "aa"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v0

    const-string v1, "1114"

    if-eqz v0, :cond_20

    invoke-virtual {p1, v1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_1d

    const-string p0, "0c280b07f92a3538bcda1aae2e8b0746"

    return-object p0

    :cond_1d
    const-string p0, "d49ab03b18a5c5e21928c59c465394d7"

    return-object p0

    :cond_20
    const-string v0, "carplay"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_2b

    const-string p0, "4b37f46239a938fa164d4a0bdc65a399"

    return-object p0

    :cond_2b
    invoke-virtual {p1, v1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_34

    const-string p0, "4b0e1de041032ee63ef0c223926d7889"

    return-object p0

    :cond_34
    const-string p0, "260459e68360a4ff5c3720d84a2acddd"

    return-object p0
.end method

.method public static final h(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .registers 5

    const-string v0, "versao"

    invoke-static {p1, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v0, "1114"

    invoke-virtual {p1, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-eqz p1, :cond_e

    goto :goto_10

    :cond_e
    const-string v0, "1111"

    :goto_10
    const-string p1, "aa"

    invoke-virtual {p0, p1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v1

    const-string v2, "v"

    if-eqz v1, :cond_1f

    invoke-static {v2, v0, p1}, Landroidx/activity/result/b;->h(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0

    :cond_1f
    const-string p1, "carplay"

    invoke-virtual {p0, p1}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_2e

    const-string p0, "cp-only"

    invoke-static {v2, v0, p0}, Landroidx/activity/result/b;->h(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0

    :cond_2e
    const-string p0, "cp"

    invoke-static {v2, v0, p0}, Landroidx/activity/result/b;->h(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static final i(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .registers 4

    const-string v0, "versao"

    invoke-static {p1, v0}, Li2/g;->e(Ljava/lang/Object;Ljava/lang/String;)V

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "connadaptor_expected_md5_"

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const/16 p1, 0x5f

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method public static j(Ljava/lang/String;)Z
    .registers 2

    const-string v0, "carplay"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-nez v0, :cond_13

    const-string v0, "ambos"

    invoke-virtual {p0, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_11

    goto :goto_13

    :cond_11
    const/4 p0, 0x0

    return p0

    :cond_13
    :goto_13
    const/4 p0, 0x1

    return p0
.end method
