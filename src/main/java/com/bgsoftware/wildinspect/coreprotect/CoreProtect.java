package com.bgsoftware.wildinspect.coreprotect;

import com.bgsoftware.wildinspect.Locale;
import com.bgsoftware.wildinspect.WildInspectPlugin;
import com.bgsoftware.wildinspect.hooks.ClaimsProvider;
import com.bgsoftware.wildinspect.utils.InspectPlayers;
import com.bgsoftware.wildinspect.utils.StringUtils;
import net.coreprotect.Functions;
import net.coreprotect.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoreProtect {

    private static final String COREPROTECT_COLOR = is116OrAbove() ? "§x§3§1§b§0§e§8" : "§3";

    private static final Pattern NO_DATA_PATTERN = Pattern.compile("%sCoreProtect §f- §fNo (.*) found for (.*)\\.".replace("%s", COREPROTECT_COLOR));
    private static final Pattern DATA_HEADER_PATTERN = Pattern.compile("§f----- %s(.*) §f----- §7\\(x(.*)/y(.*)/z(.*)\\)".replace("%s", COREPROTECT_COLOR));
    private static final Pattern DATA_LINE_PATTERN = Pattern.compile("§7(.*) ((§f|§c)-|§a\\+) %s(.*)§f(.*) %s(.*)§f\\.".replace("%s", COREPROTECT_COLOR));
    private static final Pattern DATA_FOOTER_PATTERN = Pattern.compile("§f%sPage §f(.*)/(.*)§7\\)".replace("%s", COREPROTECT_COLOR));

    private final WildInspectPlugin plugin;

    public CoreProtect(WildInspectPlugin plugin) {
        this.plugin = plugin;
    }

    public void performLookup(LookupType type, Player player, Block block, int page) {
        ClaimsProvider.ClaimPlugin claimPlugin = plugin.getHooksHandler().getRegionAt(player, block.getLocation());

        if (claimPlugin == ClaimsProvider.ClaimPlugin.NONE) {
            Locale.NOT_INSIDE_CLAIM.send(player);
            return;
        }

        if (!plugin.getHooksHandler().hasRole(claimPlugin, player, block.getLocation(), plugin.getSettings().requiredRoles)) {
            Locale.REQUIRED_ROLE.send(player, StringUtils.format(plugin.getSettings().requiredRoles));
            return;
        }

        if (InspectPlayers.isCooldown(player)) {
            DecimalFormat df = new DecimalFormat();
            df.setMaximumFractionDigits(2);
            Locale.COOLDOWN.send(player, df.format(InspectPlayers.getTimeLeft(player) / 1000));
            return;
        }

        if (plugin.getSettings().cooldown != -1)
            InspectPlayers.setCooldown(player);

        InspectPlayers.setBlock(player, block);

        if (plugin.getSettings().historyLimitPage < page) {
            Locale.LIMIT_REACH.send(player);
            return;
        }

        BlockState blockState = block.getState();

        List<String> operators = new ArrayList<>();
        Bukkit.getServer().getOperators().forEach(operator -> operators.add(operator.getName()));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                performDatabaseLookup(type, player, block, blockState, page, operators));
    }

    private void performDatabaseLookup(LookupType type, Player player, Block block, BlockState blockState, int page,
                                       List<String> ignoredPlayers) {
        try (Connection connection = Database.getConnection(true)) {
            if (connection == null) {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () ->
                        performDatabaseLookup(type, player, block, blockState, page, ignoredPlayers), 5L);
                return;
            }

            String[] resultLines;
            int maxPage;

            try (Statement statement = connection.createStatement()) {
                maxPage = getMaxPage(statement, type, player, block, blockState);

                if (maxPage <= page) {
                    Locale.LIMIT_REACH.send(player);
                    return;
                }

                switch (type) {
                    case INTERACTION_LOOKUP:
                        resultLines = CoreProtectHook.performInteractLookup(statement, player, block, page);
                        break;
                    case BLOCK_LOOKUP:
                        resultLines = CoreProtectHook.performBlockLookup(statement, player, blockState, page);
                        break;
                    case CHEST_TRANSACTIONS:
                        resultLines = CoreProtectHook.performChestLookup(statement, player, block, page);
                        break;
                    default:
                        return;
                }
            }

            Matcher matcher;

            StringBuilder message = new StringBuilder();
            boolean empty = true;

            for (String line : resultLines) {
                System.out.println(" - " + line);
                if ((matcher = NO_DATA_PATTERN.matcher(line)).matches()) {
                    switch (matcher.group(1)) {
                        case "player interactions":
                            message.append("\n").append(Locale.NO_BLOCK_INTERACTIONS.getMessage(matcher.group(2)));
                            break;
                        case "block data":
                            message.append("\n").append(Locale.NO_BLOCK_DATA.getMessage(matcher.group(2)));
                            break;
                        case "container transactions":
                            message.append("\n").append(Locale.NO_CONTAINER_TRANSACTIONS.getMessage(matcher.group(2)));
                            break;
                    }
                } else if ((matcher = DATA_HEADER_PATTERN.matcher(line)).matches()) {
                    message.append("\n").append(Locale.INSPECT_DATA_HEADER.getMessage(matcher.group(2), matcher.group(3), matcher.group(4)));
                } else if ((matcher = DATA_LINE_PATTERN.matcher(line)).matches()) {
                    if (plugin.getSettings().hideOps && ignoredPlayers.contains(matcher.group(4)))
                        continue;

                    double days = Double.parseDouble(matcher.group(1).split("/")[0].replace(",", ".")) / 24;
                    if (plugin.getSettings().historyLimitDate >= days) {
                        empty = false;
                        String timeOfAction = matcher.group(1).trim();
                        String playerAction = matcher.group(4).trim();
                        String actionType = matcher.group(5).trim();
                        String blockAction = matcher.group(6).trim();
                        message.append("\n").append(Locale.INSPECT_DATA_ROW.getMessage(timeOfAction,
                                playerAction, actionType, blockAction));
                    }
                } else if ((matcher = DATA_FOOTER_PATTERN.matcher(line)).matches()) {
                    int linePage = Integer.parseInt(matcher.group(1));
                    message.append("\n").append(Locale.INSPECT_DATA_FOOTER.getMessage(Math.max(linePage, 1),
                            Math.min(maxPage - 1, plugin.getSettings().historyLimitPage)));
                }
            }

            player.sendMessage(empty ? Locale.NO_BLOCK_DATA.getMessage("that page") : message.substring(1));
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private int getMaxPage(Statement statement, LookupType type, Player player, Block block, BlockState blockState) {
        String[] resultLines;

        int maxPage = 1;

        while (true) {
            switch (type) {
                case INTERACTION_LOOKUP:
                    resultLines = CoreProtectHook.performInteractLookup(statement, player, block, maxPage);
                    break;
                case BLOCK_LOOKUP:
                    resultLines = CoreProtectHook.performBlockLookup(statement, player, blockState, maxPage);
                    break;
                case CHEST_TRANSACTIONS:
                    resultLines = CoreProtectHook.performChestLookup(statement, player, block, maxPage);
                    break;
                default:
                    return 0;
            }

            int amountOfRows = 0;
            Matcher matcher;

            for (String line : resultLines) {
                if ((matcher = DATA_LINE_PATTERN.matcher(line)).matches()) {
                    double days = Double.parseDouble(matcher.group(1).split("/")[0].replace(",", ".")) / 24;
                    if (plugin.getSettings().historyLimitDate >= days) {
                        amountOfRows++;
                    }
                }
            }

            if (amountOfRows == 0) {
                return maxPage;
            }

            maxPage++;
        }
    }

    private static boolean is116OrAbove() {
        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        version = version.substring(1).replace("_", "").replace("R", "");
        return Integer.parseInt(version) >= 1160;
    }

    private static boolean isNewFooter() {
        try {
            Functions.getPageNavigation("", 0, 1);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

}
