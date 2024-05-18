package micheal65536.vienna.buildplate.connector.plugin;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;

import micheal65536.fountain.connector.plugin.ConnectorPlugin;
import micheal65536.fountain.connector.plugin.DisconnectResponse;
import micheal65536.fountain.connector.plugin.Inventory;
import micheal65536.fountain.connector.plugin.Logger;
import micheal65536.fountain.connector.plugin.PlayerLoginInfo;
import micheal65536.vienna.buildplate.connector.model.ConnectorPluginArg;
import micheal65536.vienna.buildplate.connector.model.InventoryResponse;
import micheal65536.vienna.buildplate.connector.model.InventoryType;
import micheal65536.vienna.buildplate.connector.model.PlayerConnectedRequest;
import micheal65536.vienna.buildplate.connector.model.PlayerConnectedResponse;
import micheal65536.vienna.buildplate.connector.model.PlayerDisconnectedRequest;
import micheal65536.vienna.buildplate.connector.model.PlayerDisconnectedResponse;
import micheal65536.vienna.buildplate.connector.model.WorldSavedMessage;
import micheal65536.vienna.eventbus.client.EventBusClient;
import micheal65536.vienna.eventbus.client.Publisher;
import micheal65536.vienna.eventbus.client.RequestSender;

import java.util.Base64;
import java.util.HashMap;

public final class ViennaConnectorPlugin implements ConnectorPlugin
{
	private String queueName;
	private EventBusClient eventBusClient;
	private Publisher publisher;
	private RequestSender requestSender;

	private boolean saveEnabled;
	private InventoryType inventoryType;

	private final HashMap<String, PlayerInventory> playerInventories = new HashMap<>();

	@Override
	public void init(@NotNull String arg, @NotNull Logger logger) throws ConnectorPluginException
	{
		ConnectorPluginArg connectorPluginArg;
		try
		{
			connectorPluginArg = new Gson().fromJson(arg, ConnectorPluginArg.class);
		}
		catch (Exception exception)
		{
			throw new ConnectorPluginException("Invalid connector plugin arg string");
		}

		try
		{
			this.eventBusClient = EventBusClient.create(connectorPluginArg.eventBusAddress());
		}
		catch (EventBusClient.ConnectException exception)
		{
			throw new ConnectorPluginException(exception);
		}
		this.queueName = connectorPluginArg.eventBusQueueName();
		this.publisher = this.eventBusClient.addPublisher();
		this.requestSender = this.eventBusClient.addRequestSender();

		this.saveEnabled = connectorPluginArg.saveEnabled();
		this.inventoryType = connectorPluginArg.inventoryType();
	}

	@Override
	public void shutdown() throws ConnectorPluginException
	{
		this.requestSender.flush();
		this.requestSender.close();
		this.publisher.flush();
		this.publisher.close();
		this.eventBusClient.close();
	}

	@Override
	public void onServerReady() throws ConnectorPluginException
	{
		EventBusHelper.publishJson(this.publisher, this.queueName, "started", null);
	}

	@Override
	public void onServerStopping() throws ConnectorPluginException
	{
		EventBusHelper.publishJson(this.publisher, this.queueName, "stopping", null);
	}

	@Override
	public void onWorldSaved(byte[] data) throws ConnectorPluginException
	{
		if (this.saveEnabled)
		{
			EventBusHelper.publishJson(this.publisher, this.queueName, "saved", new WorldSavedMessage(Base64.getEncoder().encodeToString(data)));
		}
	}

	@Override
	public boolean onPlayerConnected(@NotNull PlayerLoginInfo playerLoginInfo) throws ConnectorPluginException
	{
		PlayerConnectedResponse playerConnectedResponse = EventBusHelper.doRequestResponseSync(this.requestSender, this.queueName, "playerConnected", new PlayerConnectedRequest(playerLoginInfo.uuid, ""), PlayerConnectedResponse.class); // TODO: join code
		if (!playerConnectedResponse.accepted())
		{
			return false;
		}
		if (this.inventoryType != InventoryType.SYNCED && playerConnectedResponse.initialInventoryContents() == null)
		{
			throw new ConnectorPluginException("Bad player connected response data (missing initial inventory data for inventory type %s)".formatted(this.inventoryType.name()));
		}
		this.playerInventories.put(playerLoginInfo.uuid, switch (this.inventoryType)
		{
			case SYNCED -> new SyncedPlayerInventory(playerLoginInfo.uuid, this.requestSender, this.publisher, this.queueName);
			case DISCARD -> new DiscardPlayerInventory(playerConnectedResponse.initialInventoryContents());
			case BACKPACK -> new BackpackPlayerInventory(playerConnectedResponse.initialInventoryContents());
		});
		return true;
	}

	@Override
	@NotNull
	public DisconnectResponse onPlayerDisconnected(@NotNull String playerId) throws ConnectorPluginException
	{
		InventoryResponse backpackContents = switch (this.inventoryType)
		{
			case SYNCED -> null;
			case DISCARD -> null;
			case BACKPACK -> ((BackpackPlayerInventory) this.getInventoryForPlayer(playerId)).getContentsAsInventoryResponse();
		};
		PlayerDisconnectedRequest playerDisconnectedRequest = new PlayerDisconnectedRequest(playerId, backpackContents);
		PlayerDisconnectedResponse playerDisconnectedResponse = EventBusHelper.doRequestResponseSync(this.requestSender, this.queueName, "playerDisconnected", playerDisconnectedRequest, PlayerDisconnectedResponse.class);
		return new DisconnectResponse();
	}

	@Override
	@NotNull
	public Inventory onPlayerGetInventory(@NotNull String playerId) throws ConnectorPluginException
	{
		return this.getInventoryForPlayer(playerId).getContents();
	}

	@Override
	public void onPlayerInventoryAddItem(@NotNull String playerId, @NotNull String itemId, int count) throws ConnectorPluginException
	{
		this.getInventoryForPlayer(playerId).addItem(itemId, count);
	}

	@Override
	public void onPlayerInventoryAddItem(@NotNull String playerId, @NotNull String itemId, @NotNull String instanceId, int wear) throws ConnectorPluginException
	{
		this.getInventoryForPlayer(playerId).addItem(itemId, instanceId, wear);
	}

	@Override
	public int onPlayerInventoryRemoveItem(@NotNull String playerId, @NotNull String itemId, int count) throws ConnectorPluginException
	{
		return this.getInventoryForPlayer(playerId).removeItem(itemId, count);
	}

	@Override
	public boolean onPlayerInventoryRemoveItem(@NotNull String playerId, @NotNull String itemId, @NotNull String instanceId) throws ConnectorPluginException
	{
		return this.getInventoryForPlayer(playerId).removeItem(itemId, instanceId);
	}

	@Override
	public void onPlayerInventoryUpdateItemWear(@NotNull String playerId, @NotNull String itemId, @NotNull String instanceId, int wear) throws ConnectorPluginException
	{
		this.getInventoryForPlayer(playerId).updateItemWear(itemId, instanceId, wear);
	}

	@Override
	public void onPlayerInventorySetHotbar(@NotNull String playerId, Inventory.HotbarItem[] hotbar) throws ConnectorPluginException
	{
		this.getInventoryForPlayer(playerId).setHotbar(hotbar);
	}

	@NotNull
	private PlayerInventory getInventoryForPlayer(@NotNull String playerId) throws ConnectorPluginException
	{
		PlayerInventory playerInventory = this.playerInventories.getOrDefault(playerId, null);
		if (playerInventory == null)
		{
			throw new ConnectorPluginException("Inventory does not exist for player ID (player not connected?)");
		}
		return playerInventory;
	}
}