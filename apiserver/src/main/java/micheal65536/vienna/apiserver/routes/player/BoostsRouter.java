package micheal65536.vienna.apiserver.routes.player;

import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

import micheal65536.vienna.apiserver.routing.Request;
import micheal65536.vienna.apiserver.routing.Response;
import micheal65536.vienna.apiserver.routing.Router;
import micheal65536.vienna.apiserver.routing.ServerErrorException;
import micheal65536.vienna.apiserver.types.common.Effect;
import micheal65536.vienna.apiserver.utils.ActivityLogUtils;
import micheal65536.vienna.apiserver.utils.BoostUtils;
import micheal65536.vienna.apiserver.utils.EarthApiResponse;
import micheal65536.vienna.apiserver.utils.TimeFormatter;
import micheal65536.vienna.db.DatabaseException;
import micheal65536.vienna.db.EarthDB;
import micheal65536.vienna.db.model.player.ActivityLog;
import micheal65536.vienna.db.model.player.Boosts;
import micheal65536.vienna.db.model.player.Inventory;
import micheal65536.vienna.staticdata.Catalog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;

public class BoostsRouter extends Router
{
	public BoostsRouter(@NotNull EarthDB earthDB, @NotNull Catalog catalog)
	{
		this.addHandler(new Route.Builder(Request.Method.GET, "/boosts").build(), request ->
		{
			String playerId = request.getContextData("playerId");
			Boosts boosts;
			try
			{
				EarthDB.Results results = new EarthDB.Query(false)
						.get("boosts", playerId, Boosts.class)
						.execute(earthDB);
				boosts = (Boosts) results.get("boosts").value();
			}
			catch (DatabaseException exception)
			{
				throw new ServerErrorException(exception);
			}

			boosts.prune(request.timestamp);

			Boosts.ActiveBoost[] activeBoosts = boosts.getAll();
			Arrays.sort(activeBoosts, Comparator.comparingLong(Boosts.ActiveBoost::startTime));

			micheal65536.vienna.apiserver.types.boosts.Boosts.Potion[] potions = new micheal65536.vienna.apiserver.types.boosts.Boosts.Potion[5];
			for (int index = 0; index < potions.length && index < activeBoosts.length; index++)
			{
				Boosts.ActiveBoost activeBoost = activeBoosts[index];
				potions[index] = new micheal65536.vienna.apiserver.types.boosts.Boosts.Potion(true, activeBoost.itemId(), activeBoost.instanceId(), TimeFormatter.formatTime(activeBoost.startTime() + activeBoost.duration()));
			}

			long expiry = Long.MAX_VALUE;
			LinkedList<micheal65536.vienna.apiserver.types.boosts.Boosts.ActiveEffect> activeEffects = new LinkedList<>();
			LinkedList<micheal65536.vienna.apiserver.types.boosts.Boosts.ScenarioBoost> triggeredOnDeathBoosts = new LinkedList<>();
			for (Boosts.ActiveBoost activeBoost : activeBoosts)
			{
				long boostExpiry = activeBoost.startTime() + activeBoost.duration();
				if (boostExpiry < expiry)
				{
					expiry = boostExpiry;
				}

				Catalog.ItemsCatalog.Item item = catalog.itemsCatalog.getItem(activeBoost.itemId());
				if (item == null || item.boostInfo() == null)
				{
					continue;
				}

				if (!item.boostInfo().triggeredOnDeath())
				{
					for (Catalog.ItemsCatalog.Item.BoostInfo.Effect effect : item.boostInfo().effects())
					{
						if (effect.activation() != Catalog.ItemsCatalog.Item.BoostInfo.Effect.Activation.TIMED)
						{
							LogManager.getLogger().warn("Active boost {} has effect with activation {}", activeBoost.itemId(), effect.activation());
							continue;
						}
						long effectExpiry = activeBoost.startTime() + effect.duration();

						activeEffects.add(new micheal65536.vienna.apiserver.types.boosts.Boosts.ActiveEffect(BoostUtils.boostEffectToApiResponse(effect), TimeFormatter.formatTime(effectExpiry)));

						if (effectExpiry < expiry)
						{
							expiry = effectExpiry;
						}
					}
				}
				else
				{
					LinkedList<Effect> effects = new LinkedList<>();
					for (Catalog.ItemsCatalog.Item.BoostInfo.Effect effect : item.boostInfo().effects())
					{
						if (effect.activation() != Catalog.ItemsCatalog.Item.BoostInfo.Effect.Activation.TRIGGERED)
						{
							LogManager.getLogger().warn("Active boost {} has effect with activation {}", activeBoost.itemId(), effect.activation());
							continue;
						}
						effects.add(BoostUtils.boostEffectToApiResponse(effect));
					}
					triggeredOnDeathBoosts.add(new micheal65536.vienna.apiserver.types.boosts.Boosts.ScenarioBoost(true, activeBoost.instanceId(), effects.toArray(Effect[]::new), TimeFormatter.formatTime(boostExpiry)));
				}
			}

			HashMap<String, micheal65536.vienna.apiserver.types.boosts.Boosts.ScenarioBoost[]> scenarioBoosts = new HashMap<>();
			if (!triggeredOnDeathBoosts.isEmpty())
			{
				scenarioBoosts.put("death", triggeredOnDeathBoosts.toArray(micheal65536.vienna.apiserver.types.boosts.Boosts.ScenarioBoost[]::new));
			}

			micheal65536.vienna.apiserver.types.boosts.Boosts boostsResponse = new micheal65536.vienna.apiserver.types.boosts.Boosts(
					potions,
					new micheal65536.vienna.apiserver.types.boosts.Boosts.MiniFig[5],
					activeEffects.toArray(micheal65536.vienna.apiserver.types.boosts.Boosts.ActiveEffect[]::new),
					scenarioBoosts,
					new micheal65536.vienna.apiserver.types.boosts.Boosts.StatusEffects(null, null, null, null, null, null, null, null, null, null),    // TODO
					new HashMap<>(),
					activeBoosts.length > 0 ? TimeFormatter.formatTime(expiry) : null
			);
			return Response.okFromJson(new EarthApiResponse<>(boostsResponse), EarthApiResponse.class);
		});

		this.addHandler(new Route.Builder(Request.Method.POST, "/boosts/potions/$itemId/activate").build(), request ->
		{
			String playerId = request.getContextData("playerId");
			String itemId = request.getParameter("itemId");

			Catalog.ItemsCatalog.Item item = catalog.itemsCatalog.getItem(itemId);
			if (item == null || item.boostInfo() == null || item.boostInfo().type() != Catalog.ItemsCatalog.Item.BoostInfo.Type.POTION)
			{
				return Response.badRequest();
			}

			try
			{
				EarthDB.Results results = new EarthDB.Query(true)
						.get("inventory", playerId, Inventory.class)
						.get("boosts", playerId, Boosts.class)
						.then(results1 ->
						{
							Inventory inventory = (Inventory) results1.get("inventory").value();
							Boosts boosts = (Boosts) results1.get("boosts").value();
							boosts.prune(request.timestamp);

							if (!inventory.takeItems(itemId, 1))
							{
								return new EarthDB.Query(false);
							}
							if (boosts.getAll().length >= 5)
							{
								return new EarthDB.Query(false);
							}

							return new EarthDB.Query(true)
									.update("inventory", playerId, inventory)
									.then(ActivityLogUtils.addEntry(playerId, new ActivityLog.BoostActivatedEntry(request.timestamp, itemId)))
									.then(BoostUtils.activatePotion(playerId, itemId, request.timestamp, catalog.itemsCatalog));
						})
						.execute(earthDB);
				return Response.okFromJson(new EarthApiResponse<>(null, new EarthApiResponse.Updates(results)), EarthApiResponse.class);
			}
			catch (DatabaseException exception)
			{
				throw new ServerErrorException(exception);
			}
		});

		this.addHandler(new Route.Builder(Request.Method.DELETE, "/boosts/$instanceId").build(), request ->
		{
			String playerId = request.getContextData("playerId");
			String instanceId = request.getParameter("instanceId");

			try
			{
				EarthDB.Results results = new EarthDB.Query(true)
						.get("boosts", playerId, Boosts.class)
						.then(results1 ->
						{
							Boosts boosts = (Boosts) results1.get("boosts").value();
							boosts.prune(request.timestamp);

							Boosts.ActiveBoost activeBoost = boosts.get(instanceId);
							if (activeBoost == null)
							{
								return new EarthDB.Query(false);
							}

							Catalog.ItemsCatalog.Item item = catalog.itemsCatalog.getItem(activeBoost.itemId());
							if (item == null || item.boostInfo() == null || !item.boostInfo().canBeRemoved())
							{
								return new EarthDB.Query(false);
							}

							boosts.remove(instanceId);

							return new EarthDB.Query(true)
									.update("boosts", playerId, boosts);
						})
						.execute(earthDB);
				return Response.okFromJson(new EarthApiResponse<>(null, new EarthApiResponse.Updates(results)), EarthApiResponse.class);
			}
			catch (DatabaseException exception)
			{
				throw new ServerErrorException(exception);
			}
		});
	}
}