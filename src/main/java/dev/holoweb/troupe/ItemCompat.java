package dev.holoweb.troupe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item details scripts read and write: {@code {name, lore = {...}, enchantments = {['minecraft:sharpness'] = 3},
 * damage, model}}. NBT before 1.20.5, data components after; {@code model} is CustomModelData (an integer before
 * 1.21.4, the first float after). */
final class ItemCompat {
    private ItemCompat() {}

    /** Applies the details in {@code spec} (the keys it has) to {@code s}. */
    static void apply(ItemStack s, Map<String, Object> spec, MinecraftServer server) {
        if (spec.get("name") != null) setName(s, Component.literal(String.valueOf(spec.get("name"))));
        if (spec.get("lore") != null) {
            if (!(spec.get("lore") instanceof List<?> lines)) throw new IllegalArgumentException("lore must be a list of lines");
            List<Component> lore = new ArrayList<>();
            for (Object line : lines) lore.add(Component.literal(String.valueOf(line)));
            setLore(s, lore);
        }
        if (spec.get("enchantments") != null) {
            if (!(spec.get("enchantments") instanceof Map<?, ?> e)) throw new IllegalArgumentException("enchantments must be { id = level }");
            for (Map.Entry<?, ?> en : e.entrySet()) {
                if (!(en.getValue() instanceof Number level) || level.intValue() < 1 || level.intValue() > 255)
                    throw new IllegalArgumentException("enchantment levels are 1..255");
                enchant(s, String.valueOf(en.getKey()), level.intValue(), server);
            }
        }
        if (spec.get("damage") instanceof Number d) {
            if (!s.isDamageableItem()) throw new IllegalArgumentException("this item has no durability");
            s.setDamageValue((int) Math.max(0, Math.min(s.getMaxDamage(), d.longValue())));
        }
        if (spec.get("model") instanceof Number m) setModel(s, m.intValue());
        if (spec.get("item_model") != null) setItemModel(s, ServerApi.id(String.valueOf(spec.get("item_model"))));
        if (spec.get("glint") instanceof Boolean g) setGlint(s, g);
    }

    /** Adds what {@code s} has beyond id/count/name: lore, enchantments, damage, model. */
    static void describe(ItemStack s, Map<String, Object> out) {
        List<String> lore = new ArrayList<>();
        for (Component c : lore(s)) lore.add(c.getString());
        if (!lore.isEmpty()) out.put("lore", lore);
        Map<String, Object> ench = enchantments(s);
        if (!ench.isEmpty()) out.put("enchantments", ench);
        if (s.isDamageableItem()) {
            out.put("damage", s.getDamageValue());
            out.put("max_damage", s.getMaxDamage());
        }
        Integer model = model(s);
        if (model != null) out.put("model", model);
    }

    private static final String CUSTOM_ID = "troupe:item";

    //? if >=1.21.2 {
    /** Any item model the client has loaded (a mod's, or one from a resource's assets); nothing before 1.21.2. */
    private static void setItemModel(ItemStack s, net.minecraft.resources.Identifier model) {
        s.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, model);
    }
    //?} else {
    /*private static void setItemModel(ItemStack s, net.minecraft.resources.Identifier model) {
    }
    *///?}

    //? if >=1.20.5 {
    private static void setGlint(ItemStack s, boolean glint) {
        s.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
    }

    /** Marks {@code s} as the item a resource defined ({@code items.define}). */
    static void setCustomId(ItemStack s, String id) {
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, s, tag -> tag.putString(CUSTOM_ID, id));
    }

    static String customId(ItemStack s) {
        var data = s.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        //? if >=1.21.5 {
        String id = data.copyTag().getStringOr(CUSTOM_ID, "");
        //?} else
        /*String id = data.copyTag().getString(CUSTOM_ID);*/
        return id.isEmpty() ? null : id;
    }

    private static void setName(ItemStack s, Component name) {
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
    }

    private static void setLore(ItemStack s, List<Component> lore) {
        s.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
    }

    private static List<Component> lore(ItemStack s) {
        var lore = s.get(net.minecraft.core.component.DataComponents.LORE);
        return lore == null ? List.of() : lore.lines();
    }

    private static void enchant(ItemStack s, String id, int level, MinecraftServer server) {
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, ServerApi.id(id));
        var holder = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).get(key)
                .orElseThrow(() -> new IllegalArgumentException("unknown enchantment '" + id + "'"));
        s.enchant(holder, level);
    }

    private static Map<String, Object> enchantments(ItemStack s) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : s.getEnchantments().entrySet()) out.put(e.getKey().getRegisteredName(), e.getIntValue());
        return out;
    }
    //?} else {
    /*private static void setGlint(ItemStack s, boolean glint) {
    }

    static void setCustomId(ItemStack s, String id) {
        s.getOrCreateTag().putString(CUSTOM_ID, id);
    }

    static String customId(ItemStack s) {
        var tag = s.getTag();
        return tag != null && tag.contains(CUSTOM_ID, 8) ? tag.getString(CUSTOM_ID) : null;
    }

    private static void setName(ItemStack s, Component name) {
        s.setHoverName(name);
    }

    private static void setLore(ItemStack s, List<Component> lore) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Component c : lore) list.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(c)));
        s.getOrCreateTagElement("display").put("Lore", list);
    }

    private static List<Component> lore(ItemStack s) {
        var display = s.getTagElement("display");
        if (display == null || !display.contains("Lore", 9)) return List.of();
        List<Component> out = new ArrayList<>();
        var list = display.getList("Lore", 8);
        for (int i = 0; i < list.size(); i++) {
            Component c = Component.Serializer.fromJson(list.getString(i));
            if (c != null) out.add(c);
        }
        return out;
    }

    private static void enchant(ItemStack s, String id, int level, MinecraftServer server) {
        var e = BuiltInRegistries.ENCHANTMENT.getOptional(ServerApi.id(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown enchantment '" + id + "'"));
        s.enchant(e, level);
    }

    private static Map<String, Object> enchantments(ItemStack s) {
        Map<String, Object> out = new LinkedHashMap<>();
        net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(s)
                .forEach((e, level) -> out.put(String.valueOf(BuiltInRegistries.ENCHANTMENT.getKey(e)), level));
        return out;
    }
    *///?}

    //? if >=1.21.4 {
    private static void setModel(ItemStack s, int model) {
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                new net.minecraft.world.item.component.CustomModelData(List.of((float) model), List.of(), List.of(), List.of()));
    }

    private static Integer model(ItemStack s) {
        var cmd = s.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
        return cmd == null || cmd.floats().isEmpty() ? null : Math.round(cmd.floats().get(0));
    }
    //?} else if >=1.20.5 {
    /*private static void setModel(ItemStack s, int model) {
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(model));
    }

    private static Integer model(ItemStack s) {
        var cmd = s.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
        return cmd == null ? null : cmd.value();
    }
    *///?} else {
    /*private static void setModel(ItemStack s, int model) {
        s.getOrCreateTag().putInt("CustomModelData", model);
    }

    private static Integer model(ItemStack s) {
        var tag = s.getTag();
        return tag == null || !tag.contains("CustomModelData", 99) ? null : tag.getInt("CustomModelData");
    }
    *///?}
}
