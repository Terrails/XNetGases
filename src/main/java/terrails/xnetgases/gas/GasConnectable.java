package terrails.xnetgases.gas;

import mcjty.rftoolsbase.api.xnet.channels.IConnectable;
import mekanism.api.chemical.gas.IGasHandler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockReader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

public class GasConnectable implements IConnectable {

    @Override
    public ConnectResult canConnect(@Nonnull IBlockReader reader, @Nonnull BlockPos connectorPos, @Nonnull BlockPos blockPos, @Nullable TileEntity tile, @Nonnull Direction direction) {
        Optional<IGasHandler> optional = GasUtils.getGasHandlerFor(tile, direction);
        if (optional.isPresent()) {
            return ConnectResult.YES;
        } else return ConnectResult.DEFAULT;
    }
}
