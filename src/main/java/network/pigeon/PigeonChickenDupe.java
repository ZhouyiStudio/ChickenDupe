package network.pigeon;

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
import org.bukkit.entity.Entity;
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

public final class PigeonChickenDupe extends JavaPlugin implements Listener {

    // ==================== 数据文件 ====================
    private File dataFile;
    private YamlConfiguration dataCache;

    // ==================== 缓存 ====================
    private final Map<UUID, PlayerData> playerDataCache = new ConcurrentHashMap<>();
    private final Set<String> exeAddSet = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> vipMap = new ConcurrentHashMap<>();

    // ==================== 配置缓存（避免每 tick 读文件）====================
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

        /** 跨天自动重置 */
        void resetIfNewDay() {
            String today = LocalDate.now().toString();
            if (!today.equals(date)) {
                date = today;
                used = 0;
            }
        }

        /** 是否还能使用（limit < 0 表示无限） */
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
        // 1. 配置
        saveDefaultConfig();
        reloadConfig();
        loadConfigCache();

        // 2. 数据文件
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        // 3. 事件
        getServer().getPluginManager().registerEvents(this, this);

        // 4. 命令
        Objects.requireNonNull(getCommand("dupe")).setExecutor(new DupeCommandExecutor());

        // 5. 线程池
        workers = Executors.newFixedThreadPool(2);

        // 6. 定时刷物品
        spawnTaskId = getServer().getScheduler()
                .scheduleSyncRepeatingTask(this, this::spawnItemsForChickens, 0L, intervalTicks);

        // 7. 定时保存数据（每 5 分钟）
        saveTaskId = getServer().getScheduler()
                .scheduleSyncRepeatingTask(this, this::saveData, 6000L, 6000L);

        // 8. 启动信息
        getLogger().info(ChatColor.GREEN + "--------------------");
        getLogger().info(ChatColor.GREEN + "PigeonChickenDupe");
        getLogger().info(ChatColor.GREEN + "插件加载成功");
        getLogger().info(ChatColor.GREEN + "by Zhouyi QQ 823672854 github https://github.com/ZhouyiStudio/ChickenDupe");
        getLogger().info(ChatColor.GREEN + "--------------------");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTask(spawnTaskId);
        getServer().getScheduler().cancelTask(saveTaskId);
        saveData();    // 最后一次持久化
        workers.shutdown();
        getLogger().info(ChatColor.GREEN + "--------------------");
        getLogger().info(ChatColor.GREEN + "PigeonChickenDupe");
        getLogger().info(ChatColor.GREEN + "插件卸载成功");
        getLogger().info(ChatColor.GREEN + "--------------------");
    }

    // ==================== 配置 / 数据 加载与持久化 ====================

    private void loadConfigCache() {
        spawnInterval = getConfig().getInt("SpawnInterval", 60);
        spawnNumber   = getConfig().getInt("SpawnNumber", 1);
        defaultDailyLimit = getConfig().getInt("DailyLimit", 50);
        intervalTicks = spawnInterval * 20L;
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

        // 清空内存缓存，从文件重新加载
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
                } catch (IllegalArgumentException ignored) {
                    // 忽略损坏的 UUID 条目
                }
            }
        }
    }

    private void saveData() {
        // 将内存数据写回 dataCache
        dataCache.set("exeadd", new ArrayList<>(exeAddSet));

        // VIP
        for (Map.Entry<String, Integer> e : vipMap.entrySet()) {
            dataCache.set("vips." + e.getKey(), e.getValue());
        }

        // 玩家使用统计
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
                // /dupe  → 显示个人信息
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "控制台请使用 /dupe <exeadd|vipadd|vipdel> 子命令");
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

    /** 展示玩家自己的信息 */
    private void showPlayerInfo(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        // 等级
        String level;
        if (exeAddSet.contains(name)) {
            level = ChatColor.RED + "管理员";
        } else if (vipMap.containsKey(name)) {
            level = ChatColor.GOLD + "VIP";
        } else {
            level = ChatColor.GRAY + "普通";
        }

        // 使用数据
        PlayerData pd = playerDataCache.computeIfAbsent(uuid,
                k -> new PlayerData(LocalDate.now().toString(), 0));
        pd.resetIfNewDay();

        int max;
        if (exeAddSet.contains(name)) {
            max = -1;               // 无限
        } else if (vipMap.containsKey(name)) {
            max = vipMap.get(name);
        } else {
            max = defaultDailyLimit;
        }

        String maxStr = (max < 0) ? "∞" : String.valueOf(max);

        player.sendMessage(ChatColor.GREEN + "===== PigeonChickenDupe =====");
        player.sendMessage(ChatColor.WHITE + "用户名: " + ChatColor.YELLOW + name);
        player.sendMessage(ChatColor.WHITE + "等级: " + level);
        player.sendMessage(ChatColor.WHITE + "已使用 / 共次数: " + ChatColor.AQUA + pd.used
                + ChatColor.WHITE + " / " + ChatColor.AQUA + maxStr);
        player.sendMessage(ChatColor.GREEN + "=============================");
    }

    /** /dupe exeadd <玩家名>  — 仅控制台 */
    private boolean handleExeAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "该命令仅限控制台执行！");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /dupe exeadd <玩家名>");
            return true;
        }
        String target = args[1];
        exeAddSet.add(target);
        saveData();
        sender.sendMessage(ChatColor.GREEN + "已将 " + target + " 添加到高级用户白名单！");
        return true;
    }

    /** /dupe vipadd <玩家名> <每天次数>  — 仅控制台 */
    private boolean handleVipAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "该命令仅限控制台执行！");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "用法: /dupe vipadd <玩家名> <每天次数>");
            return true;
        }
        String target = args[1];
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
        vipMap.put(target, count);
        saveData();
        String display = (count < 0) ? "无限" : String.valueOf(count);
        sender.sendMessage(ChatColor.GREEN + "已将 " + target + " 添加到 VIP 列表，每天可 " + display + " 次！");
        return true;
    }

    /** /dupe vipdel <玩家名>  — 仅控制台 */
    private boolean handleVipDel(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "该命令仅限控制台执行！");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /dupe vipdel <玩家名>");
            return true;
        }
        String target = args[1];
        if (vipMap.remove(target) != null) {
            saveData();
            sender.sendMessage(ChatColor.GREEN + "已将 " + target + " 从 VIP 列表中移除！");
        } else {
            sender.sendMessage(ChatColor.RED + target + " 不在 VIP 列表中！");
        }
        return true;
    }

    // ==================== 事件监听 ====================

    /**
     * 玩家右键鸡 → 记录物品 + 扣除当日次数
     */
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Chicken chicken)) return;
        if (!chicken.isAdult()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        String playerName = player.getName();
        UUID playerUuid  = player.getUniqueId();

        // === 每日次数检查 ===
        PlayerData pd = playerDataCache.computeIfAbsent(playerUuid,
                k -> new PlayerData(LocalDate.now().toString(), 0));

        int limit;
        if (exeAddSet.contains(playerName)) {
            limit = -1;  // 管理员无限
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

        // === 记录物品到 data.yml ===
        pd.increment();
        String chickenUuid = chicken.getUniqueId().toString();
        workers.submit(() -> {
            // 直接写入文件（异步，不阻塞主线程）
            YamlConfiguration temp = YamlConfiguration.loadConfiguration(dataFile);
            temp.set(chickenUuid, item);
            try {
                temp.save(dataFile);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "保存鸡物品数据失败", e);
            }
        });

        // 反馈
        Location loc = chicken.getLocation();
        player.playSound(loc, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        chicken.setCustomName(ChatColor.GREEN + "[物品] " + ChatColor.GOLD + getItemDisplayName(item));
        chicken.setCustomNameVisible(true);
        player.sendMessage(ChatColor.GREEN + "已设置！今日剩余: "
                + (limit < 0 ? "∞" : String.valueOf(limit - pd.used)));
    }

    /**
     * 鸡死亡 → 清除 data.yml 中的记录
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        String chickenUuid = chicken.getUniqueId().toString();
        workers.submit(() -> {
            YamlConfiguration temp = YamlConfiguration.loadConfiguration(dataFile);
            temp.set(chickenUuid, null);
            try {
                temp.save(dataFile);
            } catch (IOException ignored) {
                // 文件删除记录失败不影响游戏
            }
        });
    }

    // ==================== 刷物品任务 ====================

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

    // ==================== 工具方法 ====================

    /**
     * 获取物品可读名称（兼容 1.21，Avoid getI18NDisplayName）
     */
    private String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        NamespacedKey key = item.getType().getKey();
        return key.getNamespace().equals("minecraft") ? key.getKey() : key.toString();
    }
}
