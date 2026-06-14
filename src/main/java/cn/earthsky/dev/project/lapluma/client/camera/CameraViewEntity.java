package cn.earthsky.dev.project.lapluma.client.camera;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

public class CameraViewEntity extends Entity {
    private float roll;
    private float fov = 70.0f;
    private CameraLens lens = new CameraLens();

    public CameraViewEntity(World worldIn) {
        super(worldIn);
        this.noClip = true;
        this.ignoreFrustumCheck = true;
        this.setSize(0.0f, 0.0f);
    }

    public void apply(CameraState state) {
        this.prevPosX = this.posX;
        this.prevPosY = this.posY;
        this.prevPosZ = this.posZ;
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;
        this.setPosition(state.position.x, state.position.y, state.position.z);
        this.rotationYaw = state.yaw;
        this.rotationPitch = state.pitch;
        this.roll = state.roll;
        this.fov = state.fov;
        this.lens = state.lens == null ? new CameraLens() : state.lens.copy();
    }

    public float getRoll() {
        return roll;
    }

    public float getFov() {
        return fov;
    }

    public CameraLens getLens() {
        return lens;
    }

    @Override
    protected void entityInit() {
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
    }

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }
}
