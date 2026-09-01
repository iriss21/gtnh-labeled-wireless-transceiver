package com.gtnhwireless.client.gui;

import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverContainer;
import com.gtnhwireless.common.wireless.LabeledWirelessTransceiverTile;
import com.gtnhwireless.network.ChannelListS2CPacket;
import com.gtnhwireless.network.PacketHandler;
import com.gtnhwireless.network.SetLabelC2SPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签收发器配置 GUI（客户端）。
 *
 * 纯配置面板（无玩家背包槽）。v0.6.0 布局：
 * - 左上：搜索框（支持拼音首字母搜索中文频道名称）；
 * - 左侧：频道列表（10 行可见，支持鼠标滚轮滚动 + 右缘滚动条），
 *   点击某一项即选中并应用到本收发器；
 * - 右侧：操作按钮竖排（添加 / 删除 / 重命名 / 锁定）；
 * - 底部：当前标签显示 + 标签输入框（回车 = 应用输入框文本）。
 *
 * 搜索功能使用 {@link PinyinSearchUtil} 支持：
 * - 普通子串匹配（英文字母/数字/符号）
 * - 拼音首字母匹配：输入拼音首字母可搜索中文频道名称
 *   （如输入 "wdpd" 可匹配 "我的频道"）
 *
 * 频道列表与「当前」标签来自客户端静态缓存 {@link ChannelListS2CPacket#LATEST} /
 * {@link ChannelListS2CPacket#LATEST_CURRENT}（服务端在打开 GUI 与每次操作后推送），
 * GUI 在 updateScreen 中轮询刷新。
 */
@SideOnly(Side.CLIENT)
public class LabeledWirelessTransceiverGui extends GuiContainer {

    private static final int BTN_ADD = 0;
    private static final int BTN_DELETE = 1;
    private static final int BTN_RENAME = 2;
    private static final int BTN_LOCK = 3;
    /** v0.5.8：收藏开关（收藏的频道置顶显示且禁止删除）。 */
    private static final int BTN_FAVORITE = 4;

    // 左侧频道列表区域（相对 GUI 左上角）
    private static final int LIST_LEFT = 8;
    private static final int LIST_TOP = 40;
    private static final int LIST_WIDTH = 112;
    private static final int LIST_ENTRY_HEIGHT = 11;
    private static final int LIST_VISIBLE_ENTRIES = 10;
    private static final int LIST_HEIGHT = LIST_VISIBLE_ENTRIES * LIST_ENTRY_HEIGHT;
    /** 列表右缘滚动条宽度（点击选择区不含滚动条）。 */
    private static final int SCROLLBAR_WIDTH = 8;
    /** 列表内容区宽度（不含滚动条）。 */
    private static final int LIST_CONTENT_WIDTH = LIST_WIDTH - SCROLLBAR_WIDTH;

    // 搜索框
    private static final int SEARCH_TOP = 22;
    private static final int SEARCH_HEIGHT = 13;

    // 底部信息区域 Y 坐标
    private static final int CURRENT_LABEL_Y = 156;
    private static final int LABEL_TEXT_Y = 168;
    private static final int LABEL_INPUT_Y = 178;
    private static final int LOCK_STATUS_Y = 194;

    // 右侧按钮列
    private static final int BTN_COL_X = 124;
    private static final int BTN_COL_W = 44;

    // 右侧按钮 Y 坐标（与列表顶部对齐）
    private static final int BTN_TOP_Y = LIST_TOP;
    private static final int BTN_GAP = 20;

    private final LabeledWirelessTransceiverTile tile;

    /** 底部标签输入框（回车发送/回车=应用标签）。 */
    private GuiTextField labelField;
    /** 搜索框（支持拼音首字母搜索中文）。 */
    private GuiTextField searchField;
    private GuiButton addButton;
    private GuiButton deleteButton;
    private GuiButton renameButton;
    private GuiButton lockButton;
    /** v0.5.8：收藏开关按钮（选中频道后点击收藏/取消收藏）。 */
    private GuiButton favoriteButton;

    /** 完整频道列表数据（label 名称列表），来自 {@link ChannelListS2CPacket#LATEST}。 */
    private final List<String> channelList = new ArrayList<>();
    /** 搜索过滤后的频道列表（用于显示）。 */
    private final List<String> filteredList = new ArrayList<>();
    /** 搜索框当前文本。 */
    private String searchQuery = "";
    /** 当前选中的列表项名称（null = 未选中）。 */
    private String selectedLabel = null;
    /** 「当前」标签的本地显示值（乐观更新 + 服务端同步）。 */
    private String localCurrentLabel = null;
    /** 上一帧方块实体描述包同步到的标签值（用于识别「新鲜的」服务端同步）。 */
    private String lastTileLabel = null;
    /**
     * v0.5.5：进行中的重命名追踪（旧名 → 新名）。
     * 重命名后旧的频道列表回显 / 旧描述包可能晚到，把「当前」显示打回旧名；
     * 追踪存在期间忽略旧名来源的覆盖，直到收到新名确认（或确认失败后清除）。
     */
    private String pendingRenameOld = null;
    private String pendingRenameNew = null;
    /** 列表滚动偏移（基于 filteredList）。 */
    private int scrollOffset = 0;
    /**
     * v0.5.8：收藏的频道名集合（客户端缓存，来自
     * {@link ChannelListS2CPacket#LATEST_FAVORITES}）。收藏的频道列表置顶 + 禁止删除。
     */
    private final java.util.Set<String> favoriteSet = new java.util.HashSet<>();

    public LabeledWirelessTransceiverGui(LabeledWirelessTransceiverContainer container,
                                         LabeledWirelessTransceiverTile tile) {
        super(container);
        this.tile = tile;
        this.xSize = 176;
        this.ySize = 204;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);

        String current = tile.getLabelForDisplay() == null ? "" : tile.getLabelForDisplay();
        this.labelField = new GuiTextField(this.fontRendererObj,
                this.guiLeft + 8, this.guiTop + LABEL_INPUT_Y, 160, 13);
        this.labelField.setText(current);
        this.labelField.setMaxStringLength(64);

        // 搜索框（位于频道列表上方，使用列表宽度）
        this.searchField = new GuiTextField(this.fontRendererObj,
                this.guiLeft + LIST_LEFT, this.guiTop + SEARCH_TOP,
                LIST_WIDTH, SEARCH_HEIGHT);
        this.searchField.setMaxStringLength(32);
        this.searchField.setEnableBackgroundDrawing(true);
        this.searchField.setVisible(true);

        // 右侧按钮列：添加 / 删除 / 重命名 / 锁定（竖排，与列表顶部对齐）
        int btnY = this.guiTop + BTN_TOP_Y;
        this.addButton = new GuiButton(BTN_ADD, this.guiLeft + BTN_COL_X, btnY,
                BTN_COL_W, 16, I18n.format("gtnhlabeledwireless.gui.add"));
        this.deleteButton = new GuiButton(BTN_DELETE, this.guiLeft + BTN_COL_X, btnY + BTN_GAP,
                BTN_COL_W, 16, I18n.format("gtnhlabeledwireless.gui.delete"));
        this.renameButton = new GuiButton(BTN_RENAME, this.guiLeft + BTN_COL_X, btnY + BTN_GAP * 2,
                BTN_COL_W, 16, I18n.format("gtnhlabeledwireless.gui.rename"));
        // v0.5.0：锁定开关（锁定后无法用扳手拆卸）
        this.lockButton = new GuiButton(BTN_LOCK, this.guiLeft + BTN_COL_X, btnY + BTN_GAP * 3,
                BTN_COL_W, 16, lockButtonLabel());
        // v0.5.8：收藏开关（选中频道后收藏/取消收藏；收藏的频道置顶且禁止删除）
        this.favoriteButton = new GuiButton(BTN_FAVORITE, this.guiLeft + BTN_COL_X, btnY + BTN_GAP * 4,
                BTN_COL_W, 16, favoriteButtonLabel());

        this.buttonList.add(this.addButton);
        this.buttonList.add(this.deleteButton);
        this.buttonList.add(this.renameButton);
        this.buttonList.add(this.lockButton);
        this.buttonList.add(this.favoriteButton);

        this.localCurrentLabel = tile.getLabelForDisplay();
        this.lastTileLabel = tile.getLabelForDisplay();
        this.refreshChannelList();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == this.addButton) {
            String text = this.labelField.getText().trim();
            if (!text.isEmpty()) {
                PacketHandler.INSTANCE.sendToServer(new SetLabelC2SPacket(
                        tile.xCoord, tile.yCoord, tile.zCoord, text));
                localCurrentLabel = text; // 乐观更新
            }
        } else if (button == this.deleteButton) {
            // v0.5.8：收藏的频道禁止删除（按钮已禁用，这里再兜底一次）
            if (selectedLabel != null && !favoriteSet.contains(selectedLabel)) {
                SetLabelC2SPacket.sendChannelAction(
                        SetLabelC2SPacket.ACTION_DELETE,
                        tile.xCoord, tile.yCoord, tile.zCoord, selectedLabel, "");
                channelList.remove(selectedLabel);
                selectedLabel = null;
                clampScroll();
            }
        } else if (button == this.renameButton) {
            if (selectedLabel != null) {
                String newLabel = this.labelField.getText().trim();
                if (!newLabel.isEmpty() && !newLabel.equals(selectedLabel)) {
                    // 仅当被重命名的频道正是本收发器当前标签时，才乐观更新"当前"显示
                    // （重命名其他频道不会改变本收发器的标签；无条件更新会闪烁错误名称）。
                    boolean wasCurrent = selectedLabel.equals(localCurrentLabel);
                    SetLabelC2SPacket.sendChannelAction(
                            SetLabelC2SPacket.ACTION_RENAME,
                            tile.xCoord, tile.yCoord, tile.zCoord, selectedLabel, newLabel);
                    // v0.5.5：登记重命名追踪，防止旧回显/旧描述包把「当前」打回旧名
                    this.pendingRenameOld = selectedLabel;
                    this.pendingRenameNew = newLabel;
                    // v0.5.8：收藏状态跟随新名（服务端回显也会纠正，本地先行保证即时排序）
                    if (favoriteSet.contains(selectedLabel)) {
                        favoriteSet.remove(selectedLabel);
                        favoriteSet.add(newLabel);
                    }
                    if (!channelList.contains(newLabel)) {
                        int idx = channelList.indexOf(selectedLabel);
                        if (idx >= 0) channelList.set(idx, newLabel);
                        else channelList.add(newLabel);
                    }
                    selectedLabel = newLabel;
                    if (wasCurrent) {
                        localCurrentLabel = newLabel; // 乐观更新
                    }
                    refreshFilter();
                }
            }
        } else if (button == this.lockButton) {
            // v0.5.0：切换锁定状态；服务端回显描述包后按钮文案刷新
            SetLabelC2SPacket.sendChannelAction(
                    SetLabelC2SPacket.ACTION_TOGGLE_LOCK,
                    tile.xCoord, tile.yCoord, tile.zCoord, "", "");
        } else if (button == this.favoriteButton) {
            // v0.5.8：收藏 / 取消收藏选中频道；服务端持久化 + 回显后由 refreshChannelList 校正权威状态
            if (selectedLabel != null) {
                SetLabelC2SPacket.sendChannelAction(
                        SetLabelC2SPacket.ACTION_TOGGLE_FAVORITE,
                        tile.xCoord, tile.yCoord, tile.zCoord, selectedLabel, "");
                if (favoriteSet.contains(selectedLabel)) {
                    favoriteSet.remove(selectedLabel);
                } else {
                    favoriteSet.add(selectedLabel);
                }
                refreshFilter(); // 置顶排序即时生效
            }
        }
    }

    /** 鼠标滚轮滚动频道列表（指针位于列表区域内时生效）。 */
    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
            // v0.5.7：修复坐标换算分子分母颠倒（此前 getEventY * displayHeight / height
            // 把 y 放大到数千，isInList 永远不命中，滚轮翻页失效）。
            int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (isInList(mx, my)) {
                scrollBy(dWheel > 0 ? -1 : 1);
            }
        }
    }

    private boolean isInList(int mx, int my) {
        return mx >= this.guiLeft + LIST_LEFT && mx < this.guiLeft + LIST_LEFT + LIST_WIDTH
                && my >= this.guiTop + LIST_TOP && my < this.guiTop + LIST_TOP + LIST_HEIGHT;
    }

    private void scrollBy(int delta) {
        int maxOffset = Math.max(0, filteredList.size() - LIST_VISIBLE_ENTRIES);
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + delta));
    }

    private void clampScroll() {
        int maxOffset = Math.max(0, filteredList.size() - LIST_VISIBLE_ENTRIES);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
    }

    @Override
    protected void keyTyped(char c, int keyCode) {
        // 搜索框处于焦点时
        if (this.searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.searchField.setFocused(false);
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                // 回车 = 搜索框中若有内容且过滤后列表不为空，选中第一项
                if (!filteredList.isEmpty()
                        && !filteredList.get(0).equals(selectedLabel)) {
                    applyListSelection(filteredList.get(0));
                }
                return;
            }
            this.searchField.textboxKeyTyped(c, keyCode);
            String newQuery = this.searchField.getText().toLowerCase();
            if (!newQuery.equals(this.searchQuery)) {
                this.searchQuery = newQuery;
                refreshFilter();
            }
            return;
        }

        // 标签输入框处于焦点时
        if (this.labelField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN) {
                String text = this.labelField.getText().trim();
                if (!text.isEmpty()) {
                    PacketHandler.INSTANCE.sendToServer(new SetLabelC2SPacket(
                            tile.xCoord, tile.yCoord, tile.zCoord, text));
                    localCurrentLabel = text; // 乐观更新
                }
                return;
            }
            this.labelField.textboxKeyTyped(c, keyCode);
            return;
        }

        // Tab 切换焦点：搜索框 ↔ 标签输入框
        if (keyCode == Keyboard.KEY_TAB) {
            if (this.searchField.isFocused()) {
                this.searchField.setFocused(false);
                this.labelField.setFocused(true);
            } else if (this.labelField.isFocused()) {
                this.labelField.setFocused(false);
                this.searchField.setFocused(true);
            } else {
                this.searchField.setFocused(true);
            }
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(c, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.labelField.mouseClicked(mouseX, mouseY, mouseButton);
        this.searchField.mouseClicked(mouseX, mouseY, mouseButton);

        // 点击列表内容区（不含滚动条）：选中并应用该频道
        int listLeft = this.guiLeft + LIST_LEFT;
        int listTop = this.guiTop + LIST_TOP;
        int listRight = listLeft + LIST_CONTENT_WIDTH;
        int listBottom = listTop + LIST_HEIGHT;

        if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
            int relY = mouseY - listTop;
            int clickedIndex = relY / LIST_ENTRY_HEIGHT + scrollOffset;
            if (clickedIndex >= 0 && clickedIndex < filteredList.size()) {
                applyListSelection(filteredList.get(clickedIndex));
            }
        }
    }

    /** 选中一个频道并应用到本收发器。 */
    private void applyListSelection(String label) {
        selectedLabel = label;
        this.labelField.setText(label);
        PacketHandler.INSTANCE.sendToServer(new SetLabelC2SPacket(
                tile.xCoord, tile.yCoord, tile.zCoord, label));
        localCurrentLabel = label; // 乐观更新
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.labelField.updateCursorCounter();
        this.searchField.updateCursorCounter();

        // v0.5.0：描述包同步的锁定状态变化时刷新按钮文案
        String label = lockButtonLabel();
        if (!label.equals(this.lockButton.displayString)) {
            this.lockButton.displayString = label;
        }

        // v0.5.8：收藏按钮文案随选中项收藏状态刷新；
        // 删除按钮对收藏频道禁用（服务端同样拒绝删除，双保险）
        String favLabel = favoriteButtonLabel();
        if (!favLabel.equals(this.favoriteButton.displayString)) {
            this.favoriteButton.displayString = favLabel;
        }
        this.favoriteButton.enabled = selectedLabel != null;
        this.deleteButton.enabled = selectedLabel != null && !favoriteSet.contains(selectedLabel);

        // v0.5.5：重命名追踪——先依据权威频道列表判断重命名是否成功。
        // 新名已出现在列表中 = 服务端已确认重命名（忽略旧名来源的覆盖，等待新名回显）；
        // 新名未出现 = 重命名失败（如目标名已占用），清除追踪并接受回显纠正回旧状态。
        boolean renamePending = pendingRenameNew != null;
        if (renamePending && !ChannelListS2CPacket.LATEST.contains(pendingRenameNew)) {
            pendingRenameOld = null;
            pendingRenameNew = null;
            renamePending = false;
        }

        // 来源 3：方块实体描述包同步——仅当值发生变化时采纳（新鲜同步，含真实清除），
        // 客户端尚未同步到的旧值（如 null）不会把「当前」打回「无」。
        String tileLabel = tile.getLabelForDisplay();
        if (renamePending && pendingRenameOld != null && pendingRenameOld.equals(tileLabel)) {
            // 重命名期间的旧名描述包：客户端 tile 尚未同步到新名，保持乐观新值
            lastTileLabel = tileLabel;
        } else {
            if (lastTileLabel == null ? tileLabel != null : !lastTileLabel.equals(tileLabel)) {
                localCurrentLabel = tileLabel;
            }
            lastTileLabel = tileLabel;
            if (renamePending && pendingRenameNew != null && pendingRenameNew.equals(tileLabel)) {
                // 权威确认（描述包已同步新名）：清除追踪
                pendingRenameOld = null;
                pendingRenameNew = null;
                renamePending = false;
            }
        }

        // 来源 2：操作回显（权威，消费一次即清空，避免过期回显覆盖之后的新状态）
        ChannelListS2CPacket.CurrentLabel current = ChannelListS2CPacket.LATEST_CURRENT;
        if (current != null) {
            ChannelListS2CPacket.LATEST_CURRENT = null;
            if (current.matches(tile.xCoord, tile.yCoord, tile.zCoord)) {
                if (renamePending && pendingRenameOld != null && pendingRenameOld.equals(current.label)) {
                    // 旧回显（重命名前的操作回显晚到）：忽略，防止把「当前」打回旧名
                } else {
                    localCurrentLabel = current.label; // 可为 null（已断开）
                    if (renamePending && pendingRenameNew != null && pendingRenameNew.equals(current.label)) {
                        // 权威确认（新名回显）：清除追踪
                        pendingRenameOld = null;
                        pendingRenameNew = null;
                        renamePending = false;
                    }
                }
            }
        }

        // 轮询静态缓存刷新频道列表（内容变化或收藏状态变化都触发）
        if (!ChannelListS2CPacket.LATEST.equals(this.channelList)
                || !ChannelListS2CPacket.LATEST_FAVORITES.equals(this.favoriteSet)) {
            refreshChannelList();
        }
    }

    /** 刷新完整频道列表 + 重新应用搜索过滤。 */
    private void refreshChannelList() {
        channelList.clear();
        channelList.addAll(ChannelListS2CPacket.LATEST);
        // v0.5.8：同步权威收藏状态（服务端回显为准，覆盖本地乐观切换）
        favoriteSet.clear();
        favoriteSet.addAll(ChannelListS2CPacket.LATEST_FAVORITES);
        if (selectedLabel != null && !channelList.contains(selectedLabel)) {
            selectedLabel = null;
        }
        refreshFilter();
    }

    /** 根据搜索关键词重新过滤列表并重置滚动偏移。 */
    private void refreshFilter() {
        filteredList.clear();
        // v0.5.8：收藏的频道置顶（稳定排序——收藏/非收藏各自保持频道原始顺序）
        java.util.List<String> fav = new java.util.ArrayList<>();
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String ch : channelList) {
            if (searchQuery.isEmpty() || PinyinSearchUtil.matches(searchQuery, ch)) {
                (favoriteSet.contains(ch) ? fav : rest).add(ch);
            }
        }
        filteredList.addAll(fav);
        filteredList.addAll(rest);
        // 检查当前选中项是否仍在过滤结果中
        if (selectedLabel != null && !filteredList.contains(selectedLabel)) {
            selectedLabel = null;
        }
        clampScroll();
    }

    /** 锁定按钮文案（随 tile 锁定状态切换，描述包同步后由 updateScreen 刷新）。 */
    private String lockButtonLabel() {
        return I18n.format(tile.isLocked()
                ? "gtnhlabeledwireless.gui.unlock"
                : "gtnhlabeledwireless.gui.lock");
    }

    /** v0.5.8：收藏按钮文案（选中频道是否已收藏 → 取消收藏 / 收藏）。 */
    private String favoriteButtonLabel() {
        return I18n.format(selectedLabel != null && favoriteSet.contains(selectedLabel)
                ? "gtnhlabeledwireless.gui.unfavorite"
                : "gtnhlabeledwireless.gui.favorite");
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int left = this.guiLeft;
        int top = this.guiTop;

        // Main background panel (covers the whole window; no inventory slots behind)
        drawRect(left, top, left + this.xSize, top + this.ySize, 0xFF212121);
        drawRect(left + 4, top + 4, left + this.xSize - 4, top + this.ySize - 4, 0xFF2E2E2E);

        // Title
        this.fontRendererObj.drawString(
                I18n.format("gtnhlabeledwireless.gui.labeled_wireless_transceiver.title"),
                left + 8, top + 8, 0xFFFFFF);

        // v0.5.5：删除「频道列表」标题（与主标题/搜索框文字重叠）

        // Search box background and text
        this.searchField.drawTextBox();

        // Channel list background
        int listLeft = left + LIST_LEFT;
        int listTop = top + LIST_TOP;
        drawRect(listLeft, listTop,
                listLeft + LIST_CONTENT_WIDTH, listTop + LIST_HEIGHT, 0xFF1A1A1A);

        // List entries
        String currentLabel = this.localCurrentLabel;
        int visibleCount = Math.min(LIST_VISIBLE_ENTRIES, filteredList.size() - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int idx = i + scrollOffset;
            if (idx >= filteredList.size()) break;
            String entry = filteredList.get(idx);
            int entryTop = listTop + i * LIST_ENTRY_HEIGHT;
            boolean isSelected = entry.equals(selectedLabel);
            boolean isCurrent = entry.equals(currentLabel);
            boolean isFav = favoriteSet.contains(entry); // v0.5.8

            if (isSelected) {
                drawRect(listLeft + 1, entryTop,
                        listLeft + LIST_CONTENT_WIDTH - 1, entryTop + LIST_ENTRY_HEIGHT - 1,
                        0xFF3D5C8A);
            }

            int textColor = isCurrent ? 0x55FF55 : (isSelected ? 0xFFFFFF : 0xCCCCCC);
            // v0.5.8：收藏条目文字右移让出星标位置
            String shown = trimToWidth(entry, LIST_CONTENT_WIDTH - 14 - (isFav ? 9 : 0));
            this.fontRendererObj.drawString(shown, listLeft + (isFav ? 13 : 3), entryTop + 1, textColor);

            if (isFav) {
                // 金色星标（★），与右侧的「当前」标记"*"区分
                this.fontRendererObj.drawString("\u2605", listLeft + 3, entryTop + 1, 0xFFD700);
            }
            if (isCurrent) {
                this.fontRendererObj.drawString("*", listLeft + LIST_CONTENT_WIDTH - 8, entryTop + 1, 0x55FF55);
            }
        }

        // Scrollbar (right edge of the list area)
        int trackX = listLeft + LIST_WIDTH - SCROLLBAR_WIDTH + 1;
        drawRect(trackX, listTop, trackX + SCROLLBAR_WIDTH - 2, listTop + LIST_HEIGHT, 0xFF111111);
        int total = filteredList.size();
        if (total > LIST_VISIBLE_ENTRIES) {
            int thumbH = Math.max(10, LIST_HEIGHT * LIST_VISIBLE_ENTRIES / total);
            int maxOff = total - LIST_VISIBLE_ENTRIES;
            int thumbY = listTop + (LIST_HEIGHT - thumbH) * scrollOffset / maxOff;
            drawRect(trackX + 1, thumbY, trackX + SCROLLBAR_WIDTH - 3, thumbY + thumbH, 0xFF666666);
        }

        // Current label display
        String currentStr = I18n.format("gtnhlabeledwireless.gui.current") + ": " +
                (currentLabel != null && !currentLabel.isEmpty()
                        ? trimToWidth(currentLabel, 130)
                        : I18n.format("gtnhlabeledwireless.gui.none"));
        this.fontRendererObj.drawString(currentStr, left + 8, top + CURRENT_LABEL_Y, 0x88FF88);

        // v0.5.0：锁定状态提示（锁定 = 扳手无法拆卸）
        String lockStr = I18n.format(tile.isLocked()
                ? "gtnhlabeledwireless.gui.locked_status"
                : "gtnhlabeledwireless.gui.unlocked_status");
        this.fontRendererObj.drawString(lockStr, left + 8, top + LOCK_STATUS_Y, tile.isLocked() ? 0xFFAA55 : 0x888888);

        // Label input field
        this.fontRendererObj.drawString(
                I18n.format("gtnhlabeledwireless.gui.label"),
                left + 8, top + LABEL_TEXT_Y, 0xAAAAAA);
        this.labelField.drawTextBox();
    }

    /** 按像素宽度截断显示文本（超长频道名不打穿 GUI 边界）。 */
    private String trimToWidth(String s, int maxWidth) {
        if (s == null) return null;
        while (s.length() > 1 && this.fontRendererObj.getStringWidth(s) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}