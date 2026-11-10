.class public final synthetic Lw0/z0;
.super Ljava/lang/Object;
.source "SourceFile"

# interfaces
.implements Lh2/a;


# instance fields
.field public final synthetic a:I

.field public final synthetic b:Landroid/car/hardware/property/CarPropertyManager;

.field public final synthetic c:I

.field public final synthetic d:I


# direct methods
.method public synthetic constructor <init>(Landroid/car/hardware/property/CarPropertyManager;III)V
    .registers 5

    iput p4, p0, Lw0/z0;->a:I

    iput-object p1, p0, Lw0/z0;->b:Landroid/car/hardware/property/CarPropertyManager;

    iput p2, p0, Lw0/z0;->c:I

    iput p3, p0, Lw0/z0;->d:I

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public final a()Ljava/lang/Object;
    .registers 5

    iget v0, p0, Lw0/z0;->a:I

    packed-switch v0, :pswitch_data_54

    iget v0, p0, Lw0/z0;->c:I

    iget v1, p0, Lw0/z0;->d:I

    iget-object v2, p0, Lw0/z0;->b:Landroid/car/hardware/property/CarPropertyManager;

    invoke-virtual {v2, v0, v1}, Landroid/car/hardware/property/CarPropertyManager;->getBooleanProperty(II)Z

    move-result v0

    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v0

    return-object v0

    :pswitch_14
    iget v0, p0, Lw0/z0;->c:I

    iget v1, p0, Lw0/z0;->d:I

    iget-object v2, p0, Lw0/z0;->b:Landroid/car/hardware/property/CarPropertyManager;

    const-class v3, Ljava/lang/Integer;

    invoke-virtual {v2, v3, v0, v1}, Landroid/car/hardware/property/CarPropertyManager;->getProperty(Ljava/lang/Class;II)Landroid/car/hardware/CarPropertyValue;

    move-result-object v0

    if-eqz v0, :cond_27

    invoke-virtual {v0}, Landroid/car/hardware/CarPropertyValue;->getValue()Ljava/lang/Object;

    move-result-object v0

    goto :goto_28

    :cond_27
    const/4 v0, 0x0

    :goto_28
    return-object v0

    :pswitch_29
    iget v0, p0, Lw0/z0;->c:I

    iget v1, p0, Lw0/z0;->d:I

    iget-object v2, p0, Lw0/z0;->b:Landroid/car/hardware/property/CarPropertyManager;

    invoke-virtual {v2, v0, v1}, Landroid/car/hardware/property/CarPropertyManager;->getIntProperty(II)I

    move-result v0

    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v0

    return-object v0

    :pswitch_38
    iget v0, p0, Lw0/z0;->c:I

    iget v1, p0, Lw0/z0;->d:I

    iget-object v2, p0, Lw0/z0;->b:Landroid/car/hardware/property/CarPropertyManager;

    invoke-virtual {v2, v0, v1}, Landroid/car/hardware/property/CarPropertyManager;->getFloatProperty(II)F

    move-result v0

    invoke-static {v0}, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;

    move-result-object v0

    return-object v0

    :pswitch_47
    iget v0, p0, Lw0/z0;->c:I

    iget v1, p0, Lw0/z0;->d:I

    iget-object v2, p0, Lw0/z0;->b:Landroid/car/hardware/property/CarPropertyManager;

    const-class v3, Ljava/lang/Integer;

    invoke-virtual {v2, v3, v0, v1}, Landroid/car/hardware/property/CarPropertyManager;->getProperty(Ljava/lang/Class;II)Landroid/car/hardware/CarPropertyValue;

    move-result-object v0

    return-object v0

    :pswitch_data_54
    .packed-switch 0x0
        :pswitch_47
        :pswitch_38
        :pswitch_29
        :pswitch_14
    .end packed-switch
.end method
