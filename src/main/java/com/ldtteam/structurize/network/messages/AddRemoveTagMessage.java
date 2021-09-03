package com.ldtteam.structurize.network.messages;

import com.ldtteam.structurize.api.util.Log;
import com.ldtteam.structurize.blocks.interfaces.IBlueprintDataProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fmllegacy.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Messages for adding or removing a tag
 */
public class AddRemoveTagMessage implements IMessage
{
    /**
     * Whether we add or remove a tag
     */
    private boolean add = false;

    /**
     * The tag to use
     */
    private String tag = "";

    /**
     * THe te's position
     */
    private final BlockPos anchorPos;

    /**
     * The tags blockpos
     */
    private final BlockPos tagPos;

    /**
     * Empty constructor used when registering the
     */
    public AddRemoveTagMessage(final FriendlyByteBuf buf)
    {
        this.add = buf.readBoolean();
        this.tag = buf.readUtf(32767);
        this.anchorPos = buf.readBlockPos();
        this.tagPos = buf.readBlockPos();
    }

    public AddRemoveTagMessage(final boolean add, final String tag, final BlockPos tagPos, final BlockPos anchorPos)
    {
        this.anchorPos = anchorPos;
        this.tagPos = tagPos;
        this.add = add;
        this.tag = tag;
    }

    @Override
    public void toBytes(final FriendlyByteBuf buf)
    {
        buf.writeBoolean(add);
        buf.writeUtf(tag);
        buf.writeBlockPos(anchorPos);
        buf.writeBlockPos(tagPos);
    }

    @Nullable
    @Override
    public LogicalSide getExecutionSide()
    {
        return LogicalSide.SERVER;
    }

    @Override
    public void onExecute(final NetworkEvent.Context ctxIn, final boolean isLogicalServer)
    {
        if (ctxIn.getSender() == null)
        {
            return;
        }

        final BlockEntity te = ctxIn.getSender().level.getBlockEntity(anchorPos);
        if (te instanceof IBlueprintDataProvider)

        {
            final IBlueprintDataProvider dataTE = (IBlueprintDataProvider) te;
            if (add)
            {
                dataTE.addTag(tagPos, tag);
            }
            else
            {
                dataTE.removeTag(tagPos, tag);
            }
        }
        else
        {
            Log.getLogger().info("Tried to add data tag to invalid tileentity:" + te);
        }
    }
}
