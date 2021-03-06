package terrails.xnetgases.gas;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import mcjty.lib.varia.WorldTools;
import mcjty.rftoolsbase.api.xnet.channels.IChannelSettings;
import mcjty.rftoolsbase.api.xnet.channels.IConnectorSettings;
import mcjty.rftoolsbase.api.xnet.channels.IControllerContext;
import mcjty.rftoolsbase.api.xnet.gui.IEditorGui;
import mcjty.rftoolsbase.api.xnet.gui.IndicatorIcon;
import mcjty.rftoolsbase.api.xnet.helper.DefaultChannelSettings;
import mcjty.rftoolsbase.api.xnet.keys.SidedConsumer;
import mcjty.xnet.XNet;
import mcjty.xnet.setup.Config;
import mekanism.api.Action;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class GasChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final ResourceLocation iconGuiElements = new ResourceLocation(XNet.MODID, "textures/gui/guielements.png");

    public static final String TAG_MODE = "mode";

    public enum ChannelMode {
        PRIORITY,
        DISTRIBUTE
    }

    private ChannelMode channelMode = ChannelMode.DISTRIBUTE;
    private int delay;
    private int roundRobinOffset;

    private List<Pair<SidedConsumer, GasConnectorSettings>> gasExtractors;
    private List<Pair<SidedConsumer, GasConnectorSettings>> gasConsumers;

    public GasChannelSettings() {
        this.delay = 0;
        this.roundRobinOffset = 0;
    }

    @Override
    public JsonObject writeToJson() {
        JsonObject object = new JsonObject();
        object.add("mode", new JsonPrimitive(channelMode.name()));
        return object;
    }

    @Override
    public void readFromJson(JsonObject data) {
        channelMode = GasUtils.getChannelModeFrom(data.get("mode").getAsString());
    }

    @Override
    public void readFromNBT(CompoundNBT nbt) {
        channelMode = ChannelMode.values()[nbt.getByte("mode")];
        this.delay = nbt.getInt("delay");
        this.roundRobinOffset = nbt.getInt("offset");
    }

    @Override
    public void writeToNBT(CompoundNBT nbt) {
        nbt.putByte("mode", (byte) channelMode.ordinal());
        nbt.putInt("delay", this.delay);
        nbt.putInt("offset", this.roundRobinOffset);
    }

    @Override
    public void tick(int channel, IControllerContext context) {
        --this.delay;
        if (this.delay <= 0) {
            this.delay = 200 * 6;
        }

        if (this.delay % 10 == 0) {
            int d = this.delay / 10;
            updateCache(channel, context);

            World world = context.getControllerWorld();
            extractorsLoop:
            for (Pair<SidedConsumer, GasConnectorSettings> entry : gasExtractors) {
                SidedConsumer consumer = entry.getFirst();
                GasConnectorSettings settings = entry.getSecond();
                if (d % settings.getSpeed() != 0) {
                    continue;
                }

                BlockPos extractorPos = context.findConsumerPosition(consumer.getConsumerId());
                if (extractorPos != null) {
                    BlockPos pos = extractorPos.offset(consumer.getSide());
                    if (!WorldTools.isLoaded(world, pos)) {
                        continue;
                    }

                    TileEntity te = world.getTileEntity(pos);
                    Optional<IGasHandler> optional = GasUtils.getGasHandlerFor(te, settings.getFacing());
                    if (optional.isPresent()) {
                        IGasHandler handler = optional.get();

                        if (checkRedstone(world, settings, extractorPos)) {
                            return;
                        }
                        if (!context.matchColor(settings.getColorsMask())) {
                            return;
                        }

                        GasStack extractMatcher = settings.getMatcher();

                        long toExtract = settings.getRate();

                        Integer count = settings.getMinmax();
                        if (count != null) {
                            long amount = GasUtils.getGasCount(handler, settings.getFacing(), extractMatcher);
                            long canExtract = amount - count;
                            if (canExtract <= 0) {
                                continue;
                            }
                            toExtract = Math.min(toExtract, canExtract);
                        }

                        if (channelMode == ChannelMode.PRIORITY) {

                            // Skip current extractor if there is one with the same gas but has higher priority.
                            if (gasExtractors.stream().anyMatch(_entry -> {
                                SidedConsumer _consumer = _entry.getFirst();
                                GasConnectorSettings _settings = _entry.getSecond();

                                if (_settings.getPriority() <= settings.getPriority()) {
                                    return false;
                                }

                                BlockPos _extractorPos = context.findConsumerPosition(_consumer.getConsumerId());
                                if (_extractorPos == null) {
                                    return false;
                                }

                                BlockPos _pos = _extractorPos.offset(_consumer.getSide());
                                if (!WorldTools.isLoaded(world, _pos)) {
                                    return false;
                                }

                                Optional<IGasHandler> _optional = GasUtils.getGasHandlerFor(world.getTileEntity(_pos), _settings.getFacing());
                                if (_optional.isPresent()) {
                                    IGasHandler _handler = _optional.get();

                                    List<Gas> handlerGases = GasUtils.getGasInTank(handler, consumer.getSide());
                                    List<Gas> _handlerGases = GasUtils.getGasInTank(_handler, _consumer.getSide());

                                    if (Collections.disjoint(handlerGases, _handlerGases)) {
                                        return false;
                                    }

                                    GasStack matcher = settings.getMatcher();
                                    GasStack _matcher = _settings.getMatcher();

                                    return (matcher == null || handlerGases.contains(matcher.getType())) && (_matcher == null || _handlerGases.contains(_matcher.getType()));
                                }
                                return false;
                            })) {
                                continue;
                            }
                        }

                        List<Pair<SidedConsumer, GasConnectorSettings>> inserted = new ArrayList<>();
                        long remaining;
                        do {
                            GasStack stack = GasUtils.extractGas(handler, toExtract, settings.getFacing(), Action.SIMULATE);
                            if (stack.isEmpty() || (extractMatcher != null && !extractMatcher.equals(stack)))
                                continue extractorsLoop;
                            toExtract = stack.getAmount();
                            inserted.clear();
                            remaining = insertGasSimulate(inserted, context, stack);
                            toExtract -= remaining;
                            if (inserted.isEmpty() || toExtract <= 0) continue extractorsLoop;
                        } while (remaining > 0);

                        if (context.checkAndConsumeRF(Config.controllerOperationRFT.get())) {
                            GasStack stack = GasUtils.extractGas(handler, toExtract, settings.getFacing(), Action.EXECUTE);
                            if (stack.isEmpty()) {
                                throw new NullPointerException(handler.getClass().getName() + " misbehaved! handler.extractGas(" + toExtract + ", Action.SIMULATE) returned null, even though handler.extractGas(" + toExtract + ", Action.EXECUTE) did not");
                            }
                            insertGasReal(context, inserted, stack);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void cleanCache() {
        this.gasExtractors = null;
        this.gasConsumers = null;
    }

    private long insertGasSimulate(@Nonnull List<Pair<SidedConsumer, GasConnectorSettings>> inserted, @Nonnull IControllerContext context, @Nonnull GasStack stack) {
        World world = context.getControllerWorld();
        long amount = stack.getAmount();
        for (int j = 0; j < gasConsumers.size(); j++) {
            int i = (j + roundRobinOffset) % gasConsumers.size();
            Pair<SidedConsumer, GasConnectorSettings> entry = gasConsumers.get(i);
            SidedConsumer consumer = entry.getFirst();
            GasConnectorSettings settings = entry.getSecond();

            if (settings.getMatcher() == null || settings.getMatcher().equals(stack)) {
                BlockPos consumerPos = context.findConsumerPosition(consumer.getConsumerId());
                if (consumerPos != null) {
                    if (!WorldTools.isLoaded(world, consumerPos)) {
                        continue;
                    }
                    if (checkRedstone(world, settings, consumerPos)) {
                        continue;
                    }
                    if (!context.matchColor(settings.getColorsMask())) {
                        continue;
                    }

                    BlockPos pos = consumerPos.offset(consumer.getSide());
                    TileEntity te = world.getTileEntity(pos);

                    Optional<IGasHandler> optional = GasUtils.getGasHandlerFor(te, settings.getFacing());
                    if (optional.isPresent()) {
                        IGasHandler handler = optional.get();

                        long toInsert = Math.min(settings.getRate(), amount);

                        Integer count = settings.getMinmax();
                        if (count != null) {
                            long a = GasUtils.getGasCount(handler, settings.getFacing(), settings.getMatcher());
                            long canInsert = count - a;
                            if (canInsert <= 0) {
                                continue;
                            }
                            toInsert = Math.min(toInsert, canInsert);
                        }

                        if (channelMode == ChannelMode.PRIORITY) {

                            // Skip current consumer if there is one that accepts the same gas but has higher priority.
                            if (gasConsumers.stream().anyMatch(_entry -> {
                                SidedConsumer _consumer = _entry.getFirst();
                                GasConnectorSettings _settings = _entry.getSecond();

                                if (_settings.getPriority() <= settings.getPriority()) {
                                    return false;
                                }

                                BlockPos _extractorPos = context.findConsumerPosition(_consumer.getConsumerId());
                                if (_extractorPos == null) {
                                    return false;
                                }

                                BlockPos _pos = _extractorPos.offset(_consumer.getSide());
                                if (!WorldTools.isLoaded(world, _pos)) {
                                    return false;
                                }

                                Optional<IGasHandler> _optional = GasUtils.getGasHandlerFor(world.getTileEntity(_pos), _settings.getFacing());
                                if (_optional.isPresent()) {
                                    IGasHandler _handler = _optional.get();

                                    List<Gas> handlerGases = GasUtils.getGasInTank(handler, consumer.getSide());
                                    List<Gas> _handlerGases = GasUtils.getGasInTank(_handler, _consumer.getSide());

                                    if (Collections.disjoint(handlerGases, _handlerGases)) {
                                        return false;
                                    }

                                    GasStack matcher = settings.getMatcher();
                                    GasStack _matcher = _settings.getMatcher();

                                    return (matcher == null || handlerGases.contains(matcher.getType())) && (_matcher == null || _handlerGases.contains(_matcher.getType()));
                                }
                                return false;
                            })) {
                                continue;
                            }
                        }

                        GasStack copy = stack.copy();
                        copy.setAmount(toInsert);

                        GasStack remaining = GasUtils.insertGas(handler, copy, settings.getFacing(), Action.SIMULATE);
                        if (remaining.isEmpty() || (!remaining.isEmpty() && copy.getAmount() != remaining.getAmount())) {
                            inserted.add(entry);
                            amount -= (copy.getAmount() - remaining.getAmount());
                            if (amount <= 0) {
                                return 0;
                            }
                        }
                    }
                }
            }
        }
        return amount;
    }

    private void insertGasReal(@Nonnull IControllerContext context, @Nonnull List<Pair<SidedConsumer, GasConnectorSettings>> inserted, @Nonnull GasStack stack) {
        long amount = stack.getAmount();
        for (Pair<SidedConsumer, GasConnectorSettings> pair : inserted) {

            GasConnectorSettings settings = pair.getSecond();
            BlockPos consumerPosition = context.findConsumerPosition(pair.getFirst().getConsumerId());

            assert consumerPosition != null;
            BlockPos pos = consumerPosition.offset(pair.getFirst().getSide());
            TileEntity te = context.getControllerWorld().getTileEntity(pos);

            Optional<IGasHandler> optional = GasUtils.getGasHandlerFor(te, settings.getFacing());
            if (optional.isPresent()) {
                IGasHandler handler = optional.get();

                long toInsert = Math.min(settings.getRate(), amount);

                Integer count = settings.getMinmax();
                if (count != null) {
                    long a = GasUtils.getGasCount(handler, settings.getFacing(), settings.getMatcher());
                    long caninsert = count - a;
                    if (caninsert <= 0) {
                        continue;
                    }
                    toInsert = Math.min(toInsert, caninsert);
                }

                GasStack copy = stack.copy();
                copy.setAmount(toInsert);

                GasStack remaining = GasUtils.insertGas(handler, copy, settings.getFacing(), Action.EXECUTE);
                if (remaining.isEmpty() || (!remaining.isEmpty() && copy.getAmount() != remaining.getAmount())) {
                    roundRobinOffset = (roundRobinOffset + 1) % gasConsumers.size();
                    amount -= (copy.getAmount() - remaining.getAmount());
                    if (amount <= 0) {
                        return;
                    }
                }
            }
        }
    }

    private void updateCache(int channel, IControllerContext context) {
        if (this.gasExtractors == null) {
            this.gasExtractors = new ArrayList<>();
            this.gasConsumers = new ArrayList<>();

            Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
            Iterator<Map.Entry<SidedConsumer, IConnectorSettings>> iterator = connectors.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<SidedConsumer, IConnectorSettings> entry = iterator.next();
                SidedConsumer consumer = entry.getKey();
                GasConnectorSettings settings = (GasConnectorSettings) entry.getValue();
                if (settings.getGasMode() == GasConnectorSettings.GasMode.EXT) {
                    this.gasExtractors.add(Pair.of(consumer, settings));
                } else {
                    this.gasConsumers.add(Pair.of(entry.getKey(), settings));
                }
            }

            connectors = context.getRoutedConnectors(channel);
            iterator = connectors.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<SidedConsumer, IConnectorSettings> entry = iterator.next();
                SidedConsumer consumer = entry.getKey();
                GasConnectorSettings settings = (GasConnectorSettings) entry.getValue();
                if (settings.getGasMode() == GasConnectorSettings.GasMode.INS) {
                    this.gasConsumers.add(Pair.of(consumer, settings));
                }
            }

            this.gasConsumers.sort((o1, o2) -> (o2.getSecond()).getPriority().compareTo((o1.getSecond()).getPriority()));
        }
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(iconGuiElements, 0, 90, 11, 10);
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        gui.nl().choices(TAG_MODE, "Gas distribution mode", this.channelMode, GasChannelSettings.ChannelMode.values());
    }

    @Override
    public void update(Map<String, Object> data) {
        this.channelMode = GasChannelSettings.ChannelMode.valueOf(((String) data.get(TAG_MODE)).toUpperCase());
    }

    @Override
    public int getColors() {
        return 0;
    }
}