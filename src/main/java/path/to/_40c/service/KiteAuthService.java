package path.to._40c.service;

import java.io.IOException;
import java.util.List;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import path.to._40c.entity.KiteAuthDetails;
import path.to._40c.repo.KiteAuthDetailsRepository;
import path.to._40c.util.TradeUtil;

import static path.to._40c.util.Constants.*;

@Service
public class KiteAuthService {

	private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

	@Autowired
    private KiteAuthDetailsRepository kiteRepository;
    
	@Autowired
    private TradeUtil util;
	
    @Transactional
    public String saveKiteAuth(String requestToken){
    	KiteConnect kiteConnect = getKiteObject();
        User user = null;
        kiteConnect.setSessionExpiryHook(new SessionExpiryHook() {
            @Override
            public void sessionExpired() {
                log.error("session expired");
            }
        });
        try {
			user =  kiteConnect.generateSession(requestToken, JANANI_APISECRET);
			KiteAuthDetails auth = new KiteAuthDetails();
		      auth.setRequestToken(requestToken);
		      auth.setAccessToken(user.accessToken);
		      auth.setPublicToken(user.publicToken);
		      auth.setApiKey(JANANI_APIKEY);
		      auth.setApiSecret(JANANI_APISECRET);
		      kiteRepository.saveAndFlush(auth);
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
    	KiteConnect kiteConnect = new KiteConnect(JANANI_APIKEY);
        kiteConnect.setUserId(JANANI_USER_ID);
        return kiteConnect;
    }
    
    public List<String> getNiftyInstruments() {
    	return util.getNiftyInstruments();
    }
}
