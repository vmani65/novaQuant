package path.to._40c.service;

import java.io.IOException;
import java.util.List;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import path.to._40c.entity.KiteAuthDetails;
import path.to._40c.repo.KiteAuthDetailsRepository;
import path.to._40c.util.TradeUtil;

@Service
public class KiteAuthService {

	private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

	@Value("${kite.api-key}")
	private String apiKey;

	@Value("${kite.api-secret}")
	private String apiSecret;

	@Value("${kite.user-id}")
	private String userId;

	@Autowired
    private KiteAuthDetailsRepository kiteRepository;

	@Autowired
    private TradeUtil util;

    @Transactional
    public String saveKiteAuth(String requestToken) {
    	KiteConnect kiteConnect = getKiteObject();
        kiteConnect.setSessionExpiryHook(new SessionExpiryHook() {
            @Override
            public void sessionExpired() {
                log.error("session expired");
            }
        });
        try {
			User user = kiteConnect.generateSession(requestToken, apiSecret);
			KiteAuthDetails auth = new KiteAuthDetails();
		    auth.setRequestToken(requestToken);
		    auth.setAccessToken(user.accessToken);
		    auth.setPublicToken(user.publicToken);
		    auth.setApiKey(apiKey);
		    auth.setApiSecret(apiSecret);
		    kiteRepository.saveAndFlush(auth);
		    util.invalidateKiteCache();
		    return "SUCCESS";
		} catch (JSONException | IOException | KiteException e) {
			log.error("Exception while generating session");
			return "FAILURE - Session Expired. Regenerate Session";
		}
    }

    public String getLoginUrl() {
        return getKiteObject().getLoginURL();
    }

    public KiteConnect getKiteObject() {
    	KiteConnect kiteConnect = new KiteConnect(apiKey);
        kiteConnect.setUserId(userId);
        return kiteConnect;
    }

    public List<String> getNiftyInstruments() {
    	return util.getNiftyInstruments();
    }
}
