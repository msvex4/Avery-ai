package com.avery.ai;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.network.NetworkSide;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Эвери: настоящий серверный игрок без клиента (как «фейк-игрок» в Carpet).
 * Тело: ходьба, прыжки, плавание, добыча, крафт, бой, сон, инвентарь.
 * Мозги (чат + нейросеть) живут в AveryBrain.
 */
public class AveryPlayer extends ServerPlayerEntity {
	private static final Random RNG = new Random();

	// ---------- задачи ----------

	public abstract static class Task {
		/** Что написать в чат, когда задача закончилась (null = молча). */
		public String doneMessage;

		/** @return true, когда задача завершена. */
		public abstract boolean tick(AveryPlayer bot);

		public abstract String describe();
	}

	private Task task;
	private Vec3d wanderTarget;
	private int wanderTimer = 80;
	private int stuckTicks;
	private Vec3d lastStuckPos = Vec3d.ZERO;

	public AveryPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
		super(server, world, profile);
	}

	// ---------- появление ----------

	public static AveryPlayer spawnNear(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		ServerWorld world = player.getServerWorld();
		Vec3d look = player.getRotationVec(1.0f);
		Vec3d p = player.getPos().add(look.x * 2.0, 0, look.z * 2.0);
		BlockPos bp = BlockPos.ofFloored(p);
		if (!world.getBlockState(bp).isAir() || !world.getBlockState(bp.up()).isAir()) {
			p = player.getPos();
		}
		return spawn(server, world, p, player.getYaw() + 180f);
	}

	public static AveryPlayer spawn(MinecraftServer server, ServerWorld world, Vec3d pos, float yaw) {
		GameProfile profile = new GameProfile(AveryMod.BOT_UUID, AveryConfig.get().botName);
		AveryPlayer bot = new AveryPlayer(server, world, profile);
		// Сообщение «Avery подключился к игре» выводит сам Майнкрафт.
		server.getPlayerManager().onPlayerConnect(new FakeClientConnection(NetworkSide.SERVERBOUND), bot);
		bot.teleport(world, pos.x, pos.y, pos.z, yaw, 0f);
		if (bot.getHealth() <= 0f) {
			bot.setHealth(bot.getMaxHealth());
			bot.getHungerManager().setFoodLevel(20);
		}
		return bot;
	}

	@Override
	public void tick() {
		if (getServer().getTicks() % 10 == 0) {
			networkHandler.syncWithPlayerPosition();
			getServerWorld().getChunkManager().updatePosition(this);
		}
		try {
			super.tick();
			playerTick();
		} catch (NullPointerException ignored) {
			// у бота нет настоящего клиента, редкие NPE в сетевом коде безвредны
		}
	}

	// ---------- «мозг тела»: вызывается каждый тик ----------

	public void brainTick() {
		updateLook();
		reactToAttack();
		if (age % 60 == 0 && !isSleeping()) scanInteresting();
		if (getHungerManager().getFoodLevel() <= 14 && age % 40 == 0) eatNow();
		if (task != null) {
			boolean done;
			try {
				done = task.tick(this);
			} catch (RuntimeException e) {
				AveryMod.LOGGER.warn("Ошибка в задаче Эвери", e);
				done = true;
			}
			if (done) {
				Task t = task;
				task = null;
				stopWalking();
				if (t.doneMessage != null) AveryBrain.report(this, t.doneMessage);
				AveryBrain.soon();
			}
		} else if (!isSleeping()) {
			idleBehavior();
		}
		if (!isSleeping() && (task == null || task instanceof FollowTask)) autoDefend();
	}

	// ---------- когда его бьют ----------

	private LivingEntity lastAttacker;
	private int lastHitAge = -10000;
	private int hurtEventAge = -10000;
	private final java.util.ArrayDeque<Integer> recentHits = new java.util.ArrayDeque<>();

	public LivingEntity getLastAttacker() {
		return lastAttacker;
	}

	@Override
	public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
		boolean r = super.damage(source, amount);
		if (r && source.getAttacker() instanceof LivingEntity le && le != this) {
			lastAttacker = le;
			lastHitAge = age;
			recentHits.addLast(age);
			while (!recentHits.isEmpty() && age - recentHits.peekFirst() > 240) recentHits.pollFirst();
		}
		return r;
	}

	private void reactToAttack() {
		LivingEntity a = lastAttacker;
		if (a == null) return;
		if (!a.isAlive() || age - lastHitAge > 200) {
			lastAttacker = null;
			return;
		}
		if (task instanceof FightTask || task instanceof FleeTask || isSleeping()) return;
		boolean player = a instanceof PlayerEntity;
		while (!recentHits.isEmpty() && age - recentHits.peekFirst() > 240) recentHits.pollFirst();
		int hits = recentHits.size();
		if (getHealth() <= 6f) {
			setTask(new FleeTask(a));
			AveryBrain.onHurt(this, a, true);
		} else if (!player || hits >= 3) {
			setTask(new FightTask(a, player ? 6 : 99));
			AveryBrain.onHurt(this, a, false);
		} else if (age - hurtEventAge > 100) {
			hurtEventAge = age;
			faceTo(a.getEyePos());
			AveryBrain.onHurt(this, a, false);
		}
	}

	public void startFight(LivingEntity target, int maxHits) {
		setTask(new FightTask(target, maxHits));
	}

	public void startFlee(LivingEntity from) {
		setTask(new FleeTask(from));
	}

	public Task getTask() {
		return task;
	}

	public void setTask(Task t) {
		this.task = t;
		stopWalking();
		resetStuck();
		wanderTarget = null;
	}

	public void stop() {
		setTask(null);
	}

	// ---------- ходьба ----------

	void walkTo(Vec3d t, boolean sprint) {
		double dx = t.x - getX();
		double dz = t.z - getZ();
		float target = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90f;
		float yaw = approach(getYaw(), target, 20f);
		setYaw(yaw);
		setHeadYaw(yaw);
		setBodyYaw(yaw);
		setPitch(approach(getPitch(), 0f, 6f));
		lookTarget = null;
		forwardSpeed = 1.0f;
		setSprinting(sprint);
		boolean jump = horizontalCollision || isTouchingWater();
		if (!jump && sprint && isOnGround() && RNG.nextInt(90) == 0) jump = true;
		setJumping(jump);
		setHeadYaw(yaw + (float) Math.sin(age * 0.09) * 5f);
	}

	/** Шаг к цели. false = застрял надолго. */
	boolean walkStep(Vec3d t, boolean sprint) {
		walkTo(t, sprint);
		if (age % 20 == 0) {
			if (getPos().squaredDistanceTo(lastStuckPos) < 0.04) stuckTicks += 20;
			else stuckTicks = 0;
			lastStuckPos = getPos();
		}
		return stuckTicks < 80;
	}

	void resetStuck() {
		stuckTicks = 0;
		lastStuckPos = getPos();
	}

	void stopWalking() {
		forwardSpeed = 0f;
		sidewaysSpeed = 0f;
		setJumping(false);
		setSprinting(false);
	}

	private double horizDist(Vec3d a) {
		double dx = a.x - getX();
		double dz = a.z - getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	public ServerPlayerEntity nearestRealPlayer(double max) {
		ServerPlayerEntity best = null;
		double bd = max * max;
		for (ServerPlayerEntity p : getServerWorld().getPlayers()) {
			if (p instanceof AveryPlayer || p.isSpectator()) continue;
			double d = p.squaredDistanceTo(this);
			if (d < bd) {
				bd = d;
				best = p;
			}
		}
		return best;
	}

	// ---------- поведение «как обычный игрок» ----------

	private void idleBehavior() {
		if (wanderTarget != null) {
			boolean ok = walkStep(wanderTarget, false);
			if (!ok || horizDist(wanderTarget) < 1.2) {
				wanderTarget = null;
				stopWalking();
				resetStuck();
				wanderTimer = 60 + RNG.nextInt(200);
			}
			return;
		}
		if (--wanderTimer <= 0) {
			ServerPlayerEntity near = nearestRealPlayer(40);
			Vec3d c = (near != null && RNG.nextInt(5) == 0) ? near.getPos() : getPos();
			double ang = RNG.nextDouble() * Math.PI * 2;
			double r = 3 + RNG.nextDouble() * 8;
			wanderTarget = new Vec3d(c.x + Math.cos(ang) * r, getY(), c.z + Math.sin(ang) * r);
			resetStuck();
		} else if (wanderTimer % 60 == 0) {
			ServerPlayerEntity near = nearestRealPlayer(8);
			if (near != null && RNG.nextInt(5) == 0) {
				lookSmooth(near.getEyePos(), 40);
			} else {
				lookSmooth(getEyePos().add(RNG.nextDouble() * 10 - 5, RNG.nextDouble() * 2 - 1, RNG.nextDouble() * 10 - 5), 30);
			}
		}
	}

	private void autoDefend() {
		if (age % 10 != 0) return;
		HostileEntity t = nearestHostile(3.6);
		if (t == null) return;
		holdBestWeapon();
		faceTo(t.getEyePos());
		tryAttack(t);
	}

	HostileEntity nearestHostile(double r) {
		List<HostileEntity> list = getServerWorld().getEntitiesByClass(HostileEntity.class,
				getBoundingBox().expand(r), e -> e.isAlive());
		HostileEntity best = null;
		double bd = Double.MAX_VALUE;
		for (HostileEntity e : list) {
			double d = e.squaredDistanceTo(this);
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	void tryAttack(LivingEntity t) {
		if (getAttackCooldownProgress(0.5f) >= 1f) {
			attack(t);
			swingHand(Hand.MAIN_HAND);
		}
	}

	void holdBestWeapon() {
		PlayerInventory inv = getInventory();
		int best = -1;
		float bd = 0f;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getStack(i);
			if (s.getItem() instanceof SwordItem sw && sw.getAttackDamage() > bd) {
				bd = sw.getAttackDamage();
				best = i;
			}
		}
		if (best >= 0) selectSlot(best);
	}

	// ---------- плавный взгляд ----------

	private Vec3d lookTarget;
	private int lookTicks;

	static float approach(float cur, float target, float step) {
		float d = MathHelper.wrapDegrees(target - cur);
		if (d > step) d = step;
		if (d < -step) d = -step;
		return cur + d;
	}

	/** Повернуться к точке не быстрее maxStep градусов за тик. */
	void turnTo(Vec3d p, float maxStep) {
		Vec3d d = p.subtract(getEyePos());
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		float ty = (float) (MathHelper.atan2(d.z, d.x) * 57.29577951308232) - 90f;
		float tp = (float) (-(MathHelper.atan2(d.y, h) * 57.29577951308232));
		float ny = approach(getYaw(), ty, maxStep);
		setYaw(ny);
		setHeadYaw(ny);
		setBodyYaw(ny);
		setPitch(approach(getPitch(), MathHelper.clamp(tp, -89f, 89f), maxStep));
	}

	void faceTo(Vec3d p) {
		turnTo(p, 28f);
	}

	void lookSmooth(Vec3d p, int ticks) {
		lookTarget = p;
		lookTicks = ticks;
	}

	private void updateLook() {
		if (lookTarget == null) return;
		if (lookTicks-- <= 0) {
			lookTarget = null;
			return;
		}
		turnTo(lookTarget, 9f);
	}

	// ---------- инвентарь ----------

	private static String path(ItemStack s) {
		return Registries.ITEM.getId(s.getItem()).getPath();
	}

	static Identifier parseId(String s) {
		if (s == null) return null;
		String n = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (n.isEmpty()) return null;
		if (!n.contains(":")) n = "minecraft:" + n;
		return Identifier.tryParse(n);
	}

	public static Item itemByName(String s) {
		Identifier id = parseId(s);
		if (id == null) return null;
		Item it = Registries.ITEM.getOrEmpty(id).orElse(null);
		return it == Items.AIR ? null : it;
	}

	public static Block blockByName(String s) {
		Identifier id = parseId(s);
		if (id == null) return null;
		Block b = Registries.BLOCK.getOrEmpty(id).orElse(null);
		return b == Blocks.AIR ? null : b;
	}

	private int matchSlot(String name) {
		String n = name.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
		if (n.startsWith("minecraft:")) n = n.substring(10);
		PlayerInventory inv = getInventory();
		int partial = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			String p = path(s);
			if (p.equals(n)) return i;
			if (partial < 0 && p.contains(n)) partial = i;
		}
		return partial;
	}

	private void selectSlot(int slot) {
		PlayerInventory inv = getInventory();
		if (slot < 9) {
			inv.selectedSlot = slot;
		} else {
			ItemStack a = inv.getStack(slot);
			ItemStack b = inv.getStack(inv.selectedSlot);
			inv.setStack(slot, b);
			inv.setStack(inv.selectedSlot, a);
		}
	}

	public boolean holdItem(String name) {
		int s = matchSlot(name);
		if (s < 0) return false;
		selectSlot(s);
		return true;
	}

	public boolean equipItem(String name) {
		int s = matchSlot(name);
		if (s < 0) return false;
		ItemStack stack = getInventory().getStack(s);
		EquipmentSlot slot = MobEntity.getPreferredEquipmentSlot(stack);
		if (slot.getType() != EquipmentSlot.Type.ARMOR && slot != EquipmentSlot.OFFHAND) return false;
		ItemStack wear = stack.split(1);
		ItemStack old = getEquippedStack(slot).copy();
		equipStack(slot, wear);
		if (!old.isEmpty()) giveToInventory(old);
		return true;
	}

	/** Выбросить предмет в сторону игрока. @return сколько выбросил. */
	public int giveTo(String name, int count, ServerPlayerEntity to) {
		int s = matchSlot(name);
		if (s < 0) return 0;
		ItemStack stack = getInventory().getStack(s);
		int n = Math.min(Math.max(count, 1), stack.getCount());
		ItemStack out = stack.split(n);
		if (to != null) faceTo(to.getEyePos());
		dropItem(out, false);
		return n;
	}

	void giveToInventory(ItemStack stack) {
		getInventory().insertStack(stack);
		if (!stack.isEmpty()) dropItem(stack, false);
	}

	public void eatNow() {
		PlayerInventory inv = getInventory();
		int best = -1;
		int bh = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !s.isFood()) continue;
			Item it = s.getItem();
			if (it == Items.ROTTEN_FLESH || it == Items.SPIDER_EYE || it == Items.PUFFERFISH
					|| it == Items.POISONOUS_POTATO || it == Items.CHICKEN || it == Items.GOLDEN_APPLE
					|| it == Items.ENCHANTED_GOLDEN_APPLE || it == Items.CHORUS_FRUIT) continue;
			int h = s.getItem().getFoodComponent().getHunger();
			if (h > bh) {
				bh = h;
				best = i;
			}
		}
		if (best >= 0) eatFood(getServerWorld(), inv.getStack(best));
	}

	public String inventorySummary() {
		Map<String, Integer> m = new LinkedHashMap<>();
		PlayerInventory inv = getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) m.merge(path(s), s.getCount(), Integer::sum);
		}
		if (m.isEmpty()) return "пусто";
		StringBuilder sb = new StringBuilder();
		m.forEach((k, v) -> sb.append(k).append(" x").append(v).append(", "));
		return sb.substring(0, sb.length() - 2);
	}

	// ---------- крафт ----------

	private boolean isNearCraftingTable() {
		BlockPos c = getBlockPos();
		ServerWorld w = getServerWorld();
		for (int dx = -4; dx <= 4; dx++)
			for (int dy = -2; dy <= 2; dy++)
				for (int dz = -4; dz <= 4; dz++)
					if (w.getBlockState(c.add(dx, dy, dz)).isOf(Blocks.CRAFTING_TABLE)) return true;
		return false;
	}

	private boolean consume(List<Ingredient> ings) {
		PlayerInventory inv = getInventory();
		int[] used = new int[36];
		for (Ingredient ing : ings) {
			boolean ok = false;
			for (int i = 0; i < 36; i++) {
				ItemStack s = inv.getStack(i);
				if (!s.isEmpty() && used[i] < s.getCount() && ing.test(s)) {
					used[i]++;
					ok = true;
					break;
				}
			}
			if (!ok) return false;
		}
		for (int i = 0; i < 36; i++) if (used[i] > 0) inv.getStack(i).decrement(used[i]);
		return true;
	}

	/** @return текст результата для чата. */
	public String craft(Item out, int count) {
		MinecraftServer server = getServer();
		boolean table = isNearCraftingTable();
		boolean needTable = false;
		boolean foundRecipe = false;
		for (CraftingRecipe r : server.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
			ItemStack res = r.getOutput(server.getRegistryManager());
			if (res.getItem() != out) continue;
			foundRecipe = true;
			if (!r.fits(2, 2) && !table) {
				needTable = true;
				continue;
			}
			List<Ingredient> ings = new ArrayList<>();
			for (Ingredient i : r.getIngredients()) if (!i.isEmpty()) ings.add(i);
			int per = Math.max(1, res.getCount());
			int times = (int) Math.ceil(Math.max(count, 1) / (double) per);
			int crafted = 0;
			for (int t = 0; t < times; t++) {
				if (!consume(ings)) break;
				giveToInventory(res.copy());
				crafted += per;
			}
			if (crafted > 0) return "Скрафтил: " + path(new ItemStack(out)) + " x" + crafted + " 🔨";
		}
		if (needTable) return "Для этого нужен верстак рядом, а его тут нет 🪵";
		if (!foundRecipe) return "Не знаю рецепта для " + path(new ItemStack(out)) + " 🤔";
		return "Не хватает материалов для " + path(new ItemStack(out)) + " 😕";
	}

	// ---------- описание состояния для нейросети ----------

	public String describeState() {
		ServerWorld w = getServerWorld();
		BlockPos p = getBlockPos();
		StringBuilder sb = new StringBuilder();
		sb.append("Позиция: ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ());
		sb.append("\nВремя суток: ").append(w.isDay() ? "день" : "ночь");
		if (w.isRaining()) sb.append(", идёт дождь");
		sb.append("\nЗдоровье: ").append((int) getHealth()).append("/20, сытость: ")
				.append(getHungerManager().getFoodLevel()).append("/20");
		sb.append("\nВ руке: ").append(idOf(getMainHandStack()));
		sb.append("\nБроня: голова=").append(idOf(getEquippedStack(EquipmentSlot.HEAD)))
				.append(", грудь=").append(idOf(getEquippedStack(EquipmentSlot.CHEST)))
				.append(", ноги=").append(idOf(getEquippedStack(EquipmentSlot.LEGS)))
				.append(", ботинки=").append(idOf(getEquippedStack(EquipmentSlot.FEET)));
		sb.append("\nИнвентарь: ").append(inventorySummary());

		StringBuilder pl = new StringBuilder();
		for (ServerPlayerEntity o : w.getPlayers()) {
			if (o instanceof AveryPlayer) continue;
			double d = Math.sqrt(o.squaredDistanceTo(this));
			if (d < 64) {
				pl.append(o.getName().getString()).append(" (").append((int) d).append(" м")
						.append(o.isSleeping() ? ", спит" : "").append("), ");
			}
		}
		sb.append("\nРядом игроки: ").append(pl.length() == 0 ? "никого" : pl.substring(0, pl.length() - 2));

		List<net.minecraft.entity.Entity> mobs = w.getOtherEntities(this, getBoundingBox().expand(16),
				e -> e instanceof LivingEntity && !(e instanceof PlayerEntity));
		StringBuilder mb = new StringBuilder();
		int shown = 0;
		for (net.minecraft.entity.Entity e : mobs) {
			if (shown++ >= 8) break;
			mb.append(Registries.ENTITY_TYPE.getId(e.getType()).getPath()).append(" (")
					.append((int) Math.sqrt(e.squaredDistanceTo(this))).append(" м), ");
		}
		sb.append("\nРядом мобы: ").append(mb.length() == 0 ? "никого" : mb.substring(0, mb.length() - 2));
		sb.append("\nНаправление взгляда: ").append(getHorizontalFacing().asString());
		sb.append("\nБлоки вокруг (радиус ~7): ").append(nearbyBlocks());
		sb.append("\nЗанятие: ").append(isSleeping() ? "спишь" : task == null ? "ничем особенным" : task.describe());
		return sb.toString();
	}

	private static String idOf(ItemStack s) {
		return s.isEmpty() ? "пусто" : path(s);
	}

	// ---------- запуск задач ----------

	public void startFollow(UUID target) {
		setTask(new FollowTask(target));
	}

	public void startGoto(Vec3d pos) {
		setTask(new GotoTask(pos));
	}

	public void startMine(Block block, int count) {
		setTask(new MineTask(block, count));
	}

	public void startPlace(Block block, List<BlockPos> targets) {
		setTask(new PlaceTask(block, targets));
	}

	public void startBreak(List<BlockPos> targets) {
		setTask(new BreakTask(targets));
	}

	public void startExplore() {
		setTask(new ExploreTask());
	}

	public void startChest(String mode, String item, int count) {
		setTask(new ChestTask(mode, item, count));
	}

	public void startAttack() {
		setTask(new AttackTask());
	}

	public void startSleep() {
		setTask(new SleepTask());
	}

	// ---------- задачи ----------

	public static class FollowTask extends Task {
		final UUID target;

		FollowTask(UUID target) {
			this.target = target;
		}

		@Override
		public boolean tick(AveryPlayer b) {
			ServerPlayerEntity p = b.getServer().getPlayerManager().getPlayer(target);
			if (p == null || p.isDead()) {
				b.stopWalking();
				return false;
			}
			if (p.getServerWorld() != b.getServerWorld()) {
				b.teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw(), 0f);
				return false;
			}
			double d = b.distanceTo(p);
			if (d > 40) {
				b.teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw(), 0f);
				b.resetStuck();
			} else if (d > 3.0) {
				boolean ok = b.walkStep(p.getPos(), d > 7);
				if (!ok && d > 8) {
					b.teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw(), 0f);
					b.resetStuck();
				}
			} else {
				b.stopWalking();
				b.resetStuck();
				if (d < 6) b.faceTo(p.getEyePos());
			}
			return false;
		}

		@Override
		public String describe() {
			return "идёшь за игроком";
		}
	}

	public static class GotoTask extends Task {
		final Vec3d pos;
		int ticks;

		GotoTask(Vec3d pos) {
			this.pos = pos;
		}

		@Override
		public boolean tick(AveryPlayer b) {
			if (b.horizDist(pos) < 1.8) return true;
			if (++ticks > 1200 || !b.walkStep(pos, b.horizDist(pos) > 8)) {
				doneMessage = "Не получается дойти 😅";
				return true;
			}
			return false;
		}

		@Override
		public String describe() {
			return "идёшь к игроку";
		}
	}

	public static class MineTask extends Task {
		final Block block;
		final int need;
		int mined;
		int breakTimer;
		int fails;
		BlockPos target;
		final Set<BlockPos> bad = new HashSet<>();

		MineTask(Block block, int need) {
			this.block = block;
			this.need = Math.max(1, Math.min(need, 64));
		}

		@Override
		public boolean tick(AveryPlayer b) {
			String name = Registries.BLOCK.getId(block).getPath();
			if (mined >= need) {
				doneMessage = "Готово, добыл " + name + " x" + mined + " ⛏️";
				return true;
			}
			ServerWorld w = b.getServerWorld();
			if (target == null || w.getBlockState(target).getBlock() != block) {
				target = b.findBlock(block, 16, bad);
				breakTimer = 0;
				b.resetStuck();
				if (target == null) {
					doneMessage = mined > 0 ? "Рядом больше нет " + name + ", добыл " + mined + " ⛏️"
							: "Не вижу рядом " + name + " 🤷";
					return true;
				}
			}
			Vec3d c = Vec3d.ofCenter(target);
			if (b.getEyePos().distanceTo(c) > 4.2) {
				b.breakTimerReset();
				if (!b.walkStep(c, false)) {
					bad.add(target);
					target = null;
					if (++fails > 5) {
						doneMessage = "Не могу добраться до " + name + " 😤";
						return true;
					}
				}
				return false;
			}
			b.stopWalking();
			BlockState st = w.getBlockState(target);
			b.equipBestTool(st);
			if (!b.canHarvest(st)) {
				doneMessage = "Для " + name + " нужен подходящий инструмент 🪓";
				return true;
			}
			b.faceTo(c);
			float hardness = st.getHardness(w, target);
			float speed = Math.max(0.5f, b.getBlockBreakingSpeed(st));
			int ticksNeeded = (int) Math.max(4, Math.ceil(hardness * 30f / speed));
			if (++breakTimer % 5 == 0) b.swingHand(Hand.MAIN_HAND);
			if (breakTimer >= ticksNeeded) {
				if (b.interactionManager.tryBreakBlock(target)) mined++;
				else bad.add(target);
				target = null;
			}
			return false;
		}

		@Override
		public String describe() {
			return "добываешь " + Registries.BLOCK.getId(block).getPath() + " (" + mined + "/" + need + ")";
		}
	}

	public static class AttackTask extends Task {
		int ticks;

		@Override
		public boolean tick(AveryPlayer b) {
			HostileEntity t = b.nearestHostile(16);
			if (t == null || ++ticks > 600) {
				doneMessage = "Вроде всех разогнал 😎";
				return true;
			}
			b.holdBestWeapon();
			double d = b.distanceTo(t);
			if (d > 2.8) {
				b.walkStep(t.getPos(), d > 6);
			} else {
				b.stopWalking();
				b.faceTo(t.getEyePos());
				b.tryAttack(t);
			}
			return false;
		}

		@Override
		public String describe() {
			return "дерёшься с монстрами";
		}
	}

	/** Идёт к ближайшей свободной кровати и ложится спать (чтобы можно было пропустить ночь). */
	public static class SleepTask extends Task {
		BlockPos bed;
		int ticks;
		boolean slept;

		@Override
		public boolean tick(AveryPlayer b) {
			if (b.isSleeping()) {
				slept = true;
				return false;
			}
			if (slept) {
				AveryBrain.onWokeUp(b);
				return true;
			}
			ServerWorld w = b.getServerWorld();
			if (w.isDay() && !w.isThundering()) return true;
			if (++ticks > 800) {
				AveryBrain.sleepFailed(b, "не смог добраться до кровати");
				return true;
			}
			if (bed == null) {
				bed = b.findBed(20);
				if (bed == null) {
					AveryBrain.sleepFailed(b, "рядом нет свободной кровати");
					return true;
				}
			}
			Vec3d c = Vec3d.ofCenter(bed);
			if (b.horizDist(c) < 2.0 && Math.abs(b.getY() - bed.getY()) < 2.0) {
				b.stopWalking();
				var r = b.trySleep(bed);
				if (r.left().isPresent()) {
					AveryBrain.sleepFailed(b, sleepReason(r.left().get().name()));
					return true;
				}
				return false;
			}
			if (!b.walkStep(c, b.horizDist(c) > 8)) {
				AveryBrain.sleepFailed(b, "не смог добраться до кровати");
				return true;
			}
			return false;
		}

		private static String sleepReason(String code) {
			return switch (code) {
				case "NOT_SAFE" -> "рядом монстры";
				case "OBSTRUCTED" -> "кровать чем-то загорожена";
				case "NOT_POSSIBLE_NOW" -> "сейчас нельзя спать";
				case "NOT_POSSIBLE_HERE" -> "здесь нельзя спать";
				case "TOO_FAR_AWAY" -> "кровать слишком далеко";
				default -> "что-то мешает лечь";
			};
		}

		@Override
		public String describe() {
			return "идёшь спать";
		}
	}

	/** Поставить блок(и) в заданные позиции. */
	public static class PlaceTask extends Task {
		final Block block;
		final List<BlockPos> targets;
		int idx;
		int ticks;
		int fails;
		int placed;

		PlaceTask(Block block, List<BlockPos> targets) {
			this.block = block;
			this.targets = targets;
		}

		@Override
		public boolean tick(AveryPlayer b) {
			String name = Registries.BLOCK.getId(block).getPath();
			if (idx >= targets.size()) {
				doneMessage = placed > 0 ? "Поставил " + name + " x" + placed + " 🧱" : "Не получилось поставить " + name + " 😕";
				return true;
			}
			if (++ticks > 900) {
				doneMessage = "Долго возился и бросил: " + name + " 😅";
				return true;
			}
			ServerWorld w = b.getServerWorld();
			BlockPos t = targets.get(idx);
			if (w.getBlockState(t).getBlock() == block) {
				idx++;
				return false;
			}
			Vec3d c = Vec3d.ofCenter(t);
			if (b.getEyePos().distanceTo(c) > 4.3) {
				if (!b.walkStep(c, false)) {
					idx++;
					fails++;
				}
				return false;
			}
			b.stopWalking();
			Item item = block.asItem();
			if (!b.holdItem(Registries.ITEM.getId(item).getPath())) {
				doneMessage = "У меня нет блока " + name + " в инвентаре 🤷";
				return true;
			}
			if (ticks % 4 != 0) return false;
			if (b.placeAt(t)) {
				placed++;
			} else {
				fails++;
			}
			idx++;
			return false;
		}

		@Override
		public String describe() {
			return "ставишь блоки " + Registries.BLOCK.getId(block).getPath();
		}
	}

	/** Сломать блок(и) по координатам. */
	public static class BreakTask extends Task {
		final List<BlockPos> targets;
		int idx;
		int timer;
		int ticks;
		int broken;

		BreakTask(List<BlockPos> targets) {
			this.targets = targets;
		}

		@Override
		public boolean tick(AveryPlayer b) {
			if (idx >= targets.size()) {
				doneMessage = broken > 0 ? "Сломал блоков: " + broken + " ⛏️" : "Там нечего ломать 🤷";
				return true;
			}
			if (++ticks > 1200) {
				doneMessage = "Не смог сломать, бросаю 😤";
				return true;
			}
			ServerWorld w = b.getServerWorld();
			BlockPos t = targets.get(idx);
			BlockState st = w.getBlockState(t);
			float hardness = st.getHardness(w, t);
			if (st.isAir() || hardness < 0f) {
				idx++;
				timer = 0;
				return false;
			}
			Vec3d c = Vec3d.ofCenter(t);
			if (b.getEyePos().distanceTo(c) > 4.3) {
				if (!b.walkStep(c, false)) {
					idx++;
					timer = 0;
				}
				return false;
			}
			b.stopWalking();
			b.equipBestTool(st);
			if (!b.canHarvest(st)) {
				doneMessage = "Нужен подходящий инструмент, чтобы это сломать 🪓";
				return true;
			}
			b.faceTo(c);
			float speed = Math.max(0.5f, b.getBlockBreakingSpeed(st));
			int need = (int) Math.max(4, Math.ceil(hardness * 30f / speed));
			if (++timer % 5 == 0) b.swingHand(Hand.MAIN_HAND);
			if (timer >= need) {
				if (b.interactionManager.tryBreakBlock(t)) broken++;
				idx++;
				timer = 0;
			}
			return false;
		}

		@Override
		public String describe() {
			return "ломаешь блоки";
		}
	}

	/** Поставить блок в позицию t, опираясь на соседний твёрдый блок. */
	boolean placeAt(BlockPos t) {
		ServerWorld w = getServerWorld();
		BlockState cur = w.getBlockState(t);
		if (!cur.isAir() && cur.getFluidState().isEmpty()) return false;
		if (getBoundingBox().intersects(new net.minecraft.util.math.Box(t))) return false;
		for (Direction d : Direction.values()) {
			BlockPos n = t.offset(d);
			BlockState ns = w.getBlockState(n);
			if (ns.isAir() || !ns.getFluidState().isEmpty()) continue;
			Direction face = d.getOpposite();
			Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
			faceTo(hit);
			BlockHitResult r = new BlockHitResult(hit, face, n, false);
			ActionResult res = interactionManager.interactBlock(this, w, getMainHandStack(), Hand.MAIN_HAND, r);
			if (res.isAccepted()) {
				swingHand(Hand.MAIN_HAND);
				return true;
			}
		}
		return false;
	}

	/** Сводка блоков вокруг для нейросети. */
	String nearbyBlocks() {
		ServerWorld w = getServerWorld();
		BlockPos c = getBlockPos();
		Map<String, Integer> m = new HashMap<>();
		for (int dx = -7; dx <= 7; dx++)
			for (int dy = -3; dy <= 6; dy++)
				for (int dz = -7; dz <= 7; dz++) {
					BlockState st = w.getBlockState(c.add(dx, dy, dz));
					if (st.isAir()) continue;
					m.merge(Registries.BLOCK.getId(st.getBlock()).getPath(), 1, Integer::sum);
				}
		if (m.isEmpty()) return "ничего";
		List<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(12, list.size()); i++) {
			sb.append(list.get(i).getKey()).append(" x").append(list.get(i).getValue()).append(", ");
		}
		return sb.substring(0, sb.length() - 2);
	}

	/** Драка с конкретной целью (моб или игрок). maxHits: сколько ударов, потом остановиться. */
	public static class FightTask extends Task {
		final LivingEntity target;
		final int maxHits;
		int hits;
		int ticks;

		FightTask(LivingEntity target, int maxHits) {
			this.target = target;
			this.maxHits = maxHits;
		}

		@Override
		public boolean tick(AveryPlayer b) {
			if (!target.isAlive() || target.isRemoved() || ++ticks > 700 || hits >= maxHits || b.distanceTo(target) > 28) {
				return true;
			}
			b.holdBestWeapon();
			double d = b.distanceTo(target);
			if (d > 2.9) {
				b.walkStep(target.getPos(), d > 6);
			} else {
				b.stopWalking();
				b.faceTo(target.getEyePos());
				if (b.getAttackCooldownProgress(0.5f) >= 1f) {
					b.attack(target);
					b.swingHand(Hand.MAIN_HAND);
					hits++;
				}
			}
			return false;
		}

		@Override
		public String describe() {
			return "дерёшься с " + target.getName().getString();
		}
	}

	/** Убегает от опасности. */
	public static class FleeTask extends Task {
		final LivingEntity from;
		int ticks;

		FleeTask(LivingEntity from) {
			this.from = from;
		}

		@Override
		public boolean tick(AveryPlayer b) {
			if (++ticks > 120 || !from.isAlive()) return true;
			Vec3d away = b.getPos().subtract(from.getPos()).normalize().multiply(10);
			b.walkStep(b.getPos().add(away), true);
			return false;
		}

		@Override
		public String describe() {
			return "убегаешь от " + from.getName().getString();
		}
	}

	/** Побродить и осмотреться: идёт в случайную сторону, обходит обрывы и лаву. */
	public static class ExploreTask extends Task {
		Vec3d target;
		int ticks;
		int turns;
		int pause;

		void pick(AveryPlayer b) {
			double ang = RNG.nextDouble() * Math.PI * 2;
			double dist = 30 + RNG.nextDouble() * 60;
			target = new Vec3d(b.getX() + Math.cos(ang) * dist, b.getY(), b.getZ() + Math.sin(ang) * dist);
			b.resetStuck();
		}

		@Override
		public boolean tick(AveryPlayer b) {
			if (pause > 0) {
				pause--;
				b.stopWalking();
				if (pause % 25 == 0) {
					b.lookSmooth(b.getEyePos().add(RNG.nextDouble() * 12 - 6, RNG.nextDouble() * 3 - 1, RNG.nextDouble() * 12 - 6), 25);
				}
				return false;
			}
			if (RNG.nextInt(500) == 0) {
				pause = 30 + RNG.nextInt(60);
				return false;
			}
			if (target == null) pick(b);
			if (++ticks > 2400 || turns > 12) return true;
			if (b.horizDist(target) < 3) return true;
			if (b.dangerAhead()) {
				turns++;
				pick(b);
				return false;
			}
			if (!b.walkStep(target, false)) {
				turns++;
				pick(b);
			}
			return false;
		}

		@Override
		public String describe() {
			return "гуляешь и исследуешь местность";
		}
	}

	/** Работа с ближайшим сундуком: положить, взять, посмотреть содержимое. */
	public static class ChestTask extends Task {
		final String mode;
		final String item;
		final int count;
		BlockPos chest;
		int ticks;

		ChestTask(String mode, String item, int count) {
			this.mode = mode;
			this.item = item == null ? "" : item;
			this.count = Math.max(1, count);
		}

		@Override
		public boolean tick(AveryPlayer b) {
			if (++ticks > 800) {
				doneMessage = "Не дошёл до сундука 😅";
				return true;
			}
			ServerWorld w = b.getServerWorld();
			if (chest == null) {
				chest = b.findContainer(16);
				if (chest == null) {
					doneMessage = "Рядом нет сундука 🤷";
					return true;
				}
			}
			Vec3d c = Vec3d.ofCenter(chest);
			if (b.getEyePos().distanceTo(c) > 3.2) {
				if (!b.walkStep(c, false)) {
					doneMessage = "Не могу подойти к сундуку 😤";
					return true;
				}
				return false;
			}
			b.stopWalking();
			b.faceTo(c);
			b.swingHand(Hand.MAIN_HAND);
			net.minecraft.inventory.Inventory inv = net.minecraft.block.entity.HopperBlockEntity.getInventoryAt(w, chest);
			if (inv == null) {
				doneMessage = "Не получается открыть сундук 🤔";
				return true;
			}
			if (mode.equals("store")) {
				doneMessage = b.storeInto(inv, item, count);
			} else if (mode.equals("take")) {
				doneMessage = b.takeFrom(inv, item, count);
			} else {
				AveryBrain.rememberChest(chest, summarize(inv));
				doneMessage = null;
			}
			return true;
		}

		@Override
		public String describe() {
			return "возишься с сундуком";
		}
	}

	// ---------- сундуки ----------

	BlockPos findContainer(int r) {
		BlockPos c = getBlockPos();
		ServerWorld w = getServerWorld();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++)
			for (int dy = -3; dy <= 3; dy++)
				for (int dz = -r; dz <= r; dz++) {
					BlockPos q = c.add(dx, dy, dz);
					Block bl = w.getBlockState(q).getBlock();
					if (!(bl instanceof net.minecraft.block.ChestBlock) && !(bl instanceof net.minecraft.block.BarrelBlock)) continue;
					double d = dx * dx + dz * dz + dy * dy;
					if (d < bd) {
						bd = d;
						best = q;
					}
				}
		return best;
	}

	static String summarize(net.minecraft.inventory.Inventory inv) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) m.merge(path(s), s.getCount(), Integer::sum);
		}
		if (m.isEmpty()) return "пусто";
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Map.Entry<String, Integer> en : m.entrySet()) {
			if (n++ >= 12) break;
			sb.append(en.getKey()).append(" x").append(en.getValue()).append(", ");
		}
		return sb.substring(0, sb.length() - 2);
	}

	static void insertInto(net.minecraft.inventory.Inventory inv, ItemStack stack) {
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) {
				inv.setStack(i, stack.copy());
				stack.setCount(0);
			} else if (ItemStack.canCombine(s, stack) && s.getCount() < s.getMaxCount()) {
				int n = Math.min(stack.getCount(), s.getMaxCount() - s.getCount());
				s.increment(n);
				stack.decrement(n);
			}
		}
	}

	String storeInto(net.minecraft.inventory.Inventory inv, String name, int count) {
		PlayerInventory pi = getInventory();
		String n = name == null ? "" : name.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
		boolean all = n.isEmpty() || n.equals("all");
		int moved = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = pi.getStack(i);
			if (s.isEmpty()) continue;
			if (all) {
				if (s.getItem() instanceof net.minecraft.item.ToolItem || s.getItem() instanceof net.minecraft.item.ArmorItem
						|| s.isFood()) continue;
			} else if (!path(s).contains(n)) {
				continue;
			}
			int want = all ? s.getCount() : Math.min(s.getCount(), count - moved);
			if (want <= 0) break;
			ItemStack part = s.copy();
			part.setCount(want);
			insertInto(inv, part);
			int did = want - part.getCount();
			s.decrement(did);
			moved += did;
			if (!all && moved >= count) break;
		}
		inv.markDirty();
		return moved > 0 ? "Разложил по сундуку: " + moved + " шт. 📦" : "Нечего класть или сундук полон 🤷";
	}

	String takeFrom(net.minecraft.inventory.Inventory inv, String name, int count) {
		String n = name == null ? "" : name.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
		int moved = 0;
		for (int i = 0; i < inv.size() && moved < count; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || !path(s).contains(n)) continue;
			int take = Math.min(s.getCount(), count - moved);
			giveToInventory(s.split(take));
			moved += take;
		}
		inv.markDirty();
		return moved > 0 ? "Взял из сундука: " + name + " x" + moved + " 📦" : "В сундуке нет «" + name + "» 🤷";
	}

	// ---------- опасности и «зрение» ----------

	/** Впереди лава, огонь, кактус или обрыв глубже 4 блоков. */
	boolean dangerAhead() {
		ServerWorld w = getServerWorld();
		for (int step = 1; step <= 2; step++) {
			BlockPos p = getBlockPos().offset(getHorizontalFacing(), step);
			boolean ground = false;
			for (int dy = 0; dy >= -4; dy--) {
				BlockState st = w.getBlockState(p.add(0, dy, 0));
				if (st.isOf(Blocks.LAVA) || st.isOf(Blocks.FIRE) || st.isOf(Blocks.MAGMA_BLOCK)
						|| st.isOf(Blocks.CACTUS) || st.isOf(Blocks.SWEET_BERRY_BUSH)) return true;
				if (st.isOf(Blocks.WATER) || !st.isAir()) {
					ground = true;
					break;
				}
			}
			if (!ground) return true;
		}
		return false;
	}

	private static final Set<String> INTEREST = Set.of("diamond_ore", "deepslate_diamond_ore", "emerald_ore",
			"deepslate_emerald_ore", "ancient_debris", "spawner", "bell", "gold_ore", "deepslate_gold_ore");

	/** Замечает ценное, только если оно открыто (рядом воздух), как живой игрок, а не рентген. */
	void scanInteresting() {
		ServerWorld w = getServerWorld();
		BlockPos c = getBlockPos();
		for (int dx = -8; dx <= 8; dx++)
			for (int dy = -5; dy <= 5; dy++)
				for (int dz = -8; dz <= 8; dz++) {
					BlockPos q = c.add(dx, dy, dz);
					BlockState st = w.getBlockState(q);
					if (st.isAir()) continue;
					String id = Registries.BLOCK.getId(st.getBlock()).getPath();
					if (!INTEREST.contains(id)) continue;
					boolean exposed = false;
					for (Direction d : Direction.values()) {
						if (w.getBlockState(q.offset(d)).isAir()) {
							exposed = true;
							break;
						}
					}
					if (exposed) {
						AveryBrain.interesting(this, id, q);
						return;
					}
				}
		if (!w.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class, getBoundingBox().expand(24), e -> true).isEmpty()) {
			AveryBrain.interesting(this, "villager (деревня рядом)", getBlockPos());
		}
	}

	// ---------- поиск блоков ----------

	void breakTimerReset() {}

	BlockPos findBlock(Block block, int r, Set<BlockPos> bad) {
		BlockPos c = getBlockPos();
		ServerWorld w = getServerWorld();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++)
			for (int dy = -6; dy <= 6; dy++)
				for (int dz = -r; dz <= r; dz++) {
					BlockPos p = c.add(dx, dy, dz);
					if (bad.contains(p) || w.getBlockState(p).getBlock() != block) continue;
					double d = dx * dx + dz * dz + dy * dy * 2.0;
					if (d < bd) {
						bd = d;
						best = p;
					}
				}
		return best;
	}

	BlockPos findBed(int r) {
		BlockPos c = getBlockPos();
		ServerWorld w = getServerWorld();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++)
			for (int dy = -8; dy <= 8; dy++)
				for (int dz = -r; dz <= r; dz++) {
					BlockPos p = c.add(dx, dy, dz);
					BlockState st = w.getBlockState(p);
					if (!(st.getBlock() instanceof BedBlock)) continue;
					if (st.get(BedBlock.PART) != BedPart.HEAD || st.get(BedBlock.OCCUPIED)) continue;
					double d = dx * dx + dz * dz + dy * dy;
					if (d < bd) {
						bd = d;
						best = p;
					}
				}
		return best;
	}

	void equipBestTool(BlockState st) {
		PlayerInventory inv = getInventory();
		int best = inv.selectedSlot;
		float bs = inv.getStack(best).getMiningSpeedMultiplier(st);
		for (int i = 0; i < 36; i++) {
			float s = inv.getStack(i).getMiningSpeedMultiplier(st);
			if (s > bs) {
				bs = s;
				best = i;
			}
		}
		if (best != inv.selectedSlot) selectSlot(best);
	}
}
