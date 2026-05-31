package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.models.User;

import path.to._40c.nqCore.entity.KiteAuthDetails;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.KiteAuthDetailsRepository;
import path.to._40c.nqCore.util.PositionUtil;

@Service
public class KiteAuthService {

	private final String apiKey;
	private final String apiSecret;
	private final KiteAuthDetailsRepository kiteRepository;
	private final KiteGateway kiteGateway;
	private final PositionUtil util;

	public KiteAuthService(
			@Value("${kite.api-key}") String apiKey,
			@Value("${kite.api-secret}") String apiSecret,
			KiteAuthDetailsRepository kiteRepository,
			KiteGateway kiteGateway,
			PositionUtil util) {
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
		this.kiteRepository = kiteRepository;
		this.kiteGateway = kiteGateway;
		this.util = util;
	}

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
        kiteRepository.deleteByAuthDateBefore(LocalDate.now(ZoneId.of(ZONE_ID)).minusDays(30));
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
