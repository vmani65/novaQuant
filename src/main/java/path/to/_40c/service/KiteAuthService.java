package path.to._40c.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.models.User;

import path.to._40c.entity.KiteAuthDetails;
import path.to._40c.gateway.KiteGateway;
import path.to._40c.repo.KiteAuthDetailsRepository;
import path.to._40c.util.TradeUtil;

@Service
public class KiteAuthService {

	private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

	@Value("${kite.api-key}")
	private String apiKey;

	@Value("${kite.api-secret}")
	private String apiSecret;

	@Autowired
    private KiteAuthDetailsRepository kiteRepository;

	@Autowired
	private KiteGateway kiteGateway;

	@Autowired
    private TradeUtil util;

    @Transactional
    public String saveKiteAuth(String requestToken) {
        User user = kiteGateway.generateSession(requestToken, apiSecret);
        if (user == null) {
            return "FAILURE - Session Expired. Regenerate Session";
        }
        KiteAuthDetails auth = new KiteAuthDetails();
        auth.setRequestToken(requestToken);
        auth.setAccessToken(user.accessToken);
        auth.setPublicToken(user.publicToken);
        auth.setApiKey(apiKey);
        auth.setApiSecret(apiSecret);
        kiteRepository.saveAndFlush(auth);
        kiteGateway.invalidateCache();
        return "SUCCESS";
    }

    public String getLoginUrl() {
        return kiteGateway.getLoginURL();
    }

    public List<String> getNiftyInstruments() {
    	return util.getNiftyInstruments();
    }
}
