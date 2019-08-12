package org.openplacereviews.opendb.service;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.ops.OpBlockChain;
import org.openplacereviews.opendb.ops.OpBlockchainRules;
import org.openplacereviews.opendb.ops.OpObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BotManager {

	private static final Log LOGGER = LogFactory.getLog(BotManager.class);

	@Autowired
	private BlocksManager blocksManager;

	private Map<String, BotInfo> bots = new TreeMap<String, BotManager.BotInfo>();

	List<Future<?>> futures = new ArrayList<>();
	ExecutorService service = Executors.newFixedThreadPool(5);
	
	public static class BotInfo {
		String api;
		String id;
		int version;
		IOpenDBBot<?> instance;
		
		public IOpenDBBot<?> getInstance() {
			return instance;
		}
	}

	@SuppressWarnings("unchecked")
	public Map<String, BotInfo> getBots() {
		OpBlockChain.ObjectsSearchRequest req = new OpBlockChain.ObjectsSearchRequest();
		req.requestCache = true;
		OpBlockChain blc = blocksManager.getBlockchain();
		blc.getObjects(OpBlockchainRules.OP_BOT, req);
		if (req.cacheObject != null) {
			return (Map<String, BotInfo>) req.cacheObject;
		}
		TreeSet<String> inits = new TreeSet<>(this.bots.keySet());
		Map<String, BotManager.BotInfo> bots = new TreeMap<String, BotManager.BotInfo>();
		for (OpObject vld : req.result) {
			BotInfo bi = new BotInfo();
			bi.id = vld.getId().get(0);
			bi.version = vld.getIntValue("version", 0);
			bi.api = vld.getStringValue("api");
			inits.remove(bi.id);
			BotInfo exBot = bots.get(bi.id);
			if (exBot == null || exBot.version != bi.version) {
				try {

					Class<?> bot = Class.forName(bi.api);
					Constructor<?> constructor = bot.getConstructor(BlocksManager.class, OpObject.class,
							JdbcTemplate.class);
					bi.instance = (IOpenDBBot<?>) constructor.newInstance(vld);
				} catch (Exception e) {
					LOGGER.error(String.format("Error while creating bot %s instance version %d, api %s", bi.id,
							bi.version, bi.api), e);
				}
			} else {
				bi = exBot;
			}
			bots.put(bi.id, bi);
		}
		this.bots = bots;
		blc.setCacheAfterSearch(req, bots);
		return bots;
	}
	
	
	public boolean startBot(String botId) {
		BotInfo botObj = getBots().get(botId);
		if (botObj == null) {
			return false;
		}
		if (botObj.instance != null) {
			futures.add(service.submit(botObj.instance));
			return true;
		}

		return false;
	}

}
