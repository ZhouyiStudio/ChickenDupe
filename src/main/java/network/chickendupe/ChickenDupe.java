package network.chickendupe;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class ChickenDupe extends JavaPlugin implements Listener {

    // ==================== 数据文件 ====================
    private File dataFile;
    private YamlConfiguration dataCache;

    // ==================== 缓存 ====================
    private final Map<UUID, PlayerData> playerDataCache = new ConcurrentHashMap<>();
    private final Set<String> exeAddSet = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> vipMap = new ConcurrentHashMap<>();

    // ==================== 配置缓存 ====================
    private int spawnInterval;
    private int spawnNumber;
    private int defaultDailyLimit;
    private long intervalTicks;

    // ==================== 调度器 ====================
    private int spawnTaskId;
    private int saveTaskId;
    private ExecutorService workers;

    // ==================== 内部数据结构 ====================
    private static class PlayerData {
        String date;   // yyyy-MM-dd
        int used;      // 今日已用次数

        PlayerData(String date, int used) {
            this.date = date;
            this.used = used;
        }

        void resetIfNewDay() {
            String today = LocalDate.now().toString();
            if (!today.equals(date)) {
                date = today;
                used = 0;
            }
        }

        boolean canUse(int limit) {
            resetIfNewDay();
            return limit < 0 || used < limit;
        }

        void increment() {
            used++;
        }
    }

    // ==================== 插件生命周期 ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfigCache();

        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("dupe")).setExecutor(new DupeCommandExecutor());

        workers = Executors.newFixedThreadPool(2);

        spawnTaskId = getServer().getScheduler()
                .scheduleSyncRepeatingTask(this, this::spawnItemsForChickens, 0L, intervalTicks);

        saveTaskId = getServer().getScheduler()
                .scheduleSyncRepeatingTask(this, this::saveData, 6000L, 6000L);

        // ★ 美化启动输出
        printBanner(true);
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTask(spawnTaskId);
        getServer().getScheduler().cancelTask(saveTaskId);
        saveData();
        workers.shutdown();
        printBanner(false);
    }

    /** 启动/卸载横幅 */
    private void printBanner(boolean enable) {
        String title = ChatColor.GOLD + "ChickenDupe" + ChatColor.WHITE + " v" + getDescription().getVersion();
        String status = enable ? ChatColor.GREEN + "✓ 加载成功！" : ChatColor.RED + "✕ 已卸载";
        String line  = ChatColor.AQUA + "╔══════════════════════════════════════╗";
        String sep   = ChatColor.AQUA + "║" + ChatColor.WHITE + "                                      " + ChatColor.AQUA + "║";
        String blank = ChatColor.AQUA + "║" + "                                        " + ChatColor.AQUA + "║";

        getLogger().info("");
        getLogger().info(line);
        getLogger().info(ChatColor.AQUA + "║        " + title + "          " + ChatColor.AQUA + "║");
        getLogger().info(sep);
        getLogger().info(ChatColor.AQUA + "║         " + status + "          " + ChatColor.AQUA + "║");
        getLogger().info(sep);
        getLogger().info(ChatColor.AQUA + "║  " + ChatColor.GRAY + "by Zhouyi" + "                          " + ChatColor.AQUA + "║");
        getLogger().info(ChatColor.AQUA + "║  " + ChatColor.GRAY + "QQ: " + ChatColor.WHITE + "823672854" + "                    " + ChatColor.AQUA + "║");
        getLogger().info(ChatColor.AQUA + "║  " + ChatColor.GRAY + "github.com/ZhouyiStudio/ChickenDupe" + "  " + ChatColor.AQUA + "║");
        getLogger().info(ChatColor.AQUA + "╚══════════════════════════════════════╝");
        getLogger().info("");
    }

    // ==================== 配置 / 数据 ====================

    private void loadConfigCache() {
        spawnInterval     = getConfig().getInt("SpawnInterval", 60);
        spawnNumber       = getConfig().getInt("SpawnNumber", 1);
        defaultDailyLimit = getConfig().getInt("DailyLimit", 50);
        intervalTicks     = spawnInterval * 20L;
    }

    @SuppressWarnings("CallToPrintStackTrace")
    private void loadData() {
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "无法创建数据文件", e);
            }
        }
        dataCache = YamlConfiguration.loadConfiguration(dataFile);

        exeAddSet.clear();
        exeAddSet.addAll(dataCache.getStringList("exeadd"));

        vipMap.clear();
        if (dataCache.contains("vips")) {
            for (String name : dataCache.getConfigurationSection("vips").getKeys(false)) {
                vipMap.put(name, dataCache.getInt("vips." + name, defaultDailyLimit));
            }
        }

        playerDataCache.clear();
        if (dataCache.contains("players")) {
            for (String uuidStr : dataCache.getConfigurationSection("players").getKeys(false)) {
                String path = "players." + uuidStr;
                String date = dataCache.getString(path + ".date", LocalDate.now().toString());
                int used    = dataCache.getInt(path + ".used", 0);
                try {
                    playerDataCache.put(UUID.fromString(uuidStr), new PlayerData(date, used));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void saveData() {
        dataCache.set("exeadd", new ArrayList<>(exeAddSet));
        for (Map.Entry<String, Integer> e : vipMap.entrySet()) {
            dataCache.set("vips." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, PlayerData> e : playerDataCache.entrySet()) {
            String path = "players." + e.getKey().toString();
            PlayerData pd = e.getValue();
            dataCache.set(path + ".date", pd.date);
            dataCache.set(path + ".used", pd.used);
        }
        try {
            dataCache.save(dataFile);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "保存数据文件失败", ex);
        }
    }

    // ==================== 命令系统 ====================

    private class DupeCommandExecutor implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "控制台请使用 /dupe <exeadd|vipadd|vipdel>");
                    return true;
                }
                showPlayerInfo(player);
                return true;
            }
            return switch (args[0].toLowerCase()) {
                case "exeadd" -> handleExeAdd(sender, args);
                case "vipadd" -> handleVipAdd(sender, args);
                case "vipdel" -> handleVipDel(sender, args);
                default -> {
                    sender.sendMessage(ChatColor.RED + "未知子命令，可用: exeadd, vipadd, vipdel");
                    yield true;
                }
            };
        }
    }

    private void showPlayerInfo(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        String level;
        if (exeAddSet.contains(name)) {
            level = ChatColor.RED + "管理员";
        } else if (vipMap.containsKey(name)) {
            level = ChatColor.GOLD + "VIP";
        } else {
            level = ChatColor.GRAY + "普通";
        }

        PlayerData pd = playerDataCache.computeIfAbsent(uuid,
                k -> new PlayerData(LocalDate.now().toString(), 0));
        pd.resetIfNewDay();

        int max;
        if (exeAddSet.contains(name)) {
            max = -1;
        } else if (vipMap.containsKey(name)) {
            max = vipMap.get(name);
        } else {
            max = defaultDailyLimit;
        }

        String maxStr = (max < 0) ? "∞" : String.valueOf(max);

        player.sendMessage(ChatColor.AQUA + "╔══════════════════════════╗");
        player.sendMessage(ChatColor.AQUA + "║    " + ChatColor.GOLD + " ChickenDupe " + ChatColor.AQUA + "        ║");
        player.sendMessage(ChatColor.AQUA + "╠══════════════════════════╣");
        player.sendMessage(ChatColor.AQUA + "║ " + ChatColor.WHITE + " 玩家: " + ChatColor.YELLOW + name + ChatColor.AQUA + "            ║");
        player.sendMessage(ChatColor.AQUA + "║ " + ChatColor.WHITE + " 等级: " + level + ChatColor.AQUA + "              ║");
        player.sendMessage(ChatColor.AQUA + "║ " + ChatColor.WHITE + " 使用: " + ChatColor.AQUA + pd.used
                + ChatColor.WHITE + " / " + ChatColor.AQUA + maxStr + ChatColor.AQUA + "           ║");
        player.sendMessage(ChatColor.AQUA + "╚══════════════════════════╝");
    }

    private boolean handleExeAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "该命令仅限控制台执行！");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /dupe exeadd <玩家名>");
            return true;
        }
        exeAddSet.add(args[1]);
        saveData();
        sender.sendMessage(ChatColor.GREEN + "已将 " + args[1] + " 添加到高级用户白名单！");
        return true;
    }

    private boolean handleVipAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "该命令仅限控制台执行！");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "用法: /dupe vipadd <玩家名> <每天次数>");
            return true;
        }
        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "次数必须是一个整数！");
            return true;
        }
        if (count <= 0 && count != -1) {
            sender.sendMessage(ChatColor.RED + "次数必须大于 0 或为 -1（无限）！");
            return true;
        }
        vipMap.put(args[1], count);
        saveData();
        String display = (count < 0) ? "无限" : String.valueOf(count);
        sender.sendMessage(ChatColor.GREEN + "已将 " + args[1] + " 添加到 VIP 列表，每天可 " + display + " 次！");
        return true;
    }

    private boolean handleVipDel(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "该命令仅限控制台执行！");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /dupe vipdel <玩家名>");
            return true;
        }
        if (vipMap.remove(args[1]) != null) {
            saveData();
            sender.sendMessage(ChatColor.GREEN + "已将 " + args[1] + " 从 VIP 列表中移除！");
        } else {
            sender.sendMessage(ChatColor.RED + args[1] + " 不在 VIP 列表中！");
        }
        return true;
    }

    // ==================== 事件监听 ====================

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Chicken chicken)) return;
        if (!chicken.isAdult()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        String playerName = player.getName();
        UUID playerUuid  = player.getUniqueId();

        PlayerData pd = playerDataCache.computeIfAbsent(playerUuid,
                k -> new PlayerData(LocalDate.now().toString(), 0));

        int limit;
        if (exeAddSet.contains(playerName)) {
            limit = -1;
        } else if (vipMap.containsKey(playerName)) {
            limit = vipMap.get(playerName);
        } else {
            limit = defaultDailyLimit;
        }

        if (!pd.canUse(limit)) {
            player.sendMessage(ChatColor.RED + "你今天已经用完了次数！上限: "
                    + (limit < 0 ? "∞" : String.valueOf(limit)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        pd.increment();
        String chickenUuid = chicken.getUniqueId().toString();
        workers.submit(() -> {
            YamlConfiguration temp = YamlConfiguration.loadConfiguration(dataFile);
            temp.set(chickenUuid, item);
            try {
                temp.save(dataFile);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "保存鸡物品数据失败", e);
            }
        });

        Location loc = chicken.getLocation();
        player.playSound(loc, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        chicken.setCustomName(ChatColor.GREEN + "[物品] " + ChatColor.GOLD + getItemDisplayName(item));
        chicken.setCustomNameVisible(true);
        player.sendMessage(ChatColor.GREEN + "已设置！今日剩余: "
                + (limit < 0 ? "∞" : String.valueOf(limit - pd.used)));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        String chickenUuid = chicken.getUniqueId().toString();
        workers.submit(() -> {
            YamlConfiguration temp = YamlConfiguration.loadConfiguration(dataFile);
            temp.set(chickenUuid, null);
            try {
                temp.save(dataFile);
            } catch (IOException ignored) {}
        });
    }

    // ==================== 刷物品 ====================

    private void spawnItemsForChickens() {
        int amount = spawnNumber;
        if (amount <= 0) return;

        for (org.bukkit.World world : getServer().getWorlds()) {
            for (Chicken chicken : world.getEntitiesByClass(Chicken.class)) {
                String key = chicken.getUniqueId().toString();
                if (!dataCache.contains(key)) continue;

                ItemStack stack = dataCache.getItemStack(key);
                if (stack == null) continue;

                ItemStack drop = stack.clone();
                drop.setAmount(amount);
                world.dropItemNaturally(chicken.getLocation(), drop);
            }
        }
    }

    // ==================== 工具 ====================

    private String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        NamespacedKey key = item.getType().getKey();
        return key.getNamespace().equals("minecraft") ? key.getKey() : key.toString();
    }
}
