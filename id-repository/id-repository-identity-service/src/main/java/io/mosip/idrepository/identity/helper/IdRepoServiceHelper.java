package io.mosip.idrepository.identity.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.mosip.idrepository.core.builder.RestRequestBuilder;
import io.mosip.idrepository.core.constant.IdRepoErrorConstants;
import io.mosip.idrepository.core.constant.RestServicesConstants;
import io.mosip.idrepository.core.dto.RestRequestDTO;
import io.mosip.idrepository.core.exception.IdRepoAppException;
import io.mosip.idrepository.core.exception.IdRepoAppUncheckedException;
import io.mosip.idrepository.core.exception.IdRepoDataValidationException;
import io.mosip.idrepository.core.exception.RestServiceException;
import io.mosip.idrepository.core.helper.RestHelper;
import io.mosip.idrepository.core.logger.IdRepoLogger;
import io.mosip.idrepository.core.repository.UinHashSaltRepo;
import io.mosip.idrepository.core.security.IdRepoSecurityManager;
import io.mosip.idrepository.identity.dto.HandleDto;
import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.idobjectvalidator.constant.IdObjectValidatorConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.mosip.idrepository.core.constant.IdRepoConstants.ROOT_PATH;
import static io.mosip.idrepository.core.constant.IdRepoConstants.SPLITTER;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.INVALID_INPUT_PARAMETER;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.MISSING_INPUT_PARAMETER;

@Component
public class IdRepoServiceHelper {

    Logger mosipLogger = IdRepoLogger.getLogger(IdRepoServiceHelper.class);

    private static final String ID_REPO_SERVICE_HELPER = "IdRepoServiceHelper";

    private static final String REQUEST = "request";

    /** Reserved keyword to hold resident chosen handle field Ids */
    private static final String SELECTED_HANDLES = "selectedHandles";

    private static final String ID_SCHEMA_VERSION = "IDSchemaVersion";

    /** The schema map. */
    private static Map<String, String> schemaMap = new HashMap<>();
    private static Map<String, List<String>> supportedHandlesInSchema = new HashMap<>();

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RestRequestBuilder restBuilder;

    @Autowired
    private RestHelper restHelper;

    @Autowired
    private IdRepoSecurityManager securityManager;

    @Autowired
    private UinHashSaltRepo uinHashSaltRepo;


    /**
     * Convert to map.
     *
     * @param identity the identity
     * @return the map
     * @throws IdRepoAppException the id repo app exception
     */
    public Map<String, Object> convertToMap(Object identity) throws IdRepoAppException {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(identity), new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_HELPER, "convertToMap", "\n" + e.getMessage());
            throw new IdRepoAppException(INVALID_INPUT_PARAMETER.getErrorCode(),
                    String.format(INVALID_INPUT_PARAMETER.getErrorMessage(), REQUEST), e);
        }
    }

    /**
     * Gets the schema.
     *
     * @param schemaVersion the schema version
     * @return the schema
     */
    public String getSchema(String schemaVersion) {
        if (Objects.isNull(schemaVersion) || schemaVersion.contentEquals("null")) {
            mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_HELPER, "getSchema",
                    "\n" + "schemaVersion is null");
            throw new IdRepoAppUncheckedException(MISSING_INPUT_PARAMETER.getErrorCode(),
                    String.format(MISSING_INPUT_PARAMETER.getErrorMessage(),
                            ROOT_PATH + IdObjectValidatorConstant.PATH_SEPERATOR + "IDSchemaVersion"));
        }
        try {
            if (schemaMap.containsKey(schemaVersion)) {
                return schemaMap.get(schemaVersion);
            } else {
                RestRequestDTO restRequest;
                restRequest = restBuilder.buildRequest(RestServicesConstants.SYNCDATA_SERVICE, null, ResponseWrapper.class);
                restRequest.setUri(restRequest.getUri().concat("?schemaVersion=" + schemaVersion));
                ResponseWrapper<Map<String, String>> response = restHelper.requestSync(restRequest);
                schemaMap.put(schemaVersion, response.getResponse().get("schemaJson"));
                supportedHandlesInSchema.put(schemaVersion, getSupportedHandles(response.getResponse().get("schemaJson")));
                return getSchema(schemaVersion);
            }
        } catch (IdRepoDataValidationException | RestServiceException e) {
            mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_HELPER, "getSchema", "\n" + e.getMessage());
            throw new IdRepoAppUncheckedException(IdRepoErrorConstants.SCHEMA_RETRIEVE_ERROR);
        }
    }

    public Map<String, HandleDto> getSelectedHandles(Object identity) throws IdRepoAppException {
        Map<String, Object> requestMap = convertToMap(identity);
        if (requestMap.containsKey(ROOT_PATH) && Objects.nonNull(requestMap.get(ROOT_PATH))) {
            Map<String, Object> identityMap = (Map<String, Object>) requestMap.get(ROOT_PATH);
            String schemaVersion = String.valueOf(identityMap.get(ID_SCHEMA_VERSION));
            if(identityMap.containsKey(SELECTED_HANDLES) && Objects.nonNull(identityMap.get(SELECTED_HANDLES))) {
                mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_HELPER, "getSelectedHandles",
                        requestMap.get(SELECTED_HANDLES));
                List<String> selectedHandleFieldIds = (List<String>) identityMap.get(SELECTED_HANDLES);
                return selectedHandleFieldIds.stream()
                        .filter( handleFieldId -> supportedHandlesInSchema.get(schemaVersion).contains(handleFieldId))
                        .collect(Collectors.toMap(handleName->handleName,
                                handleFieldId-> {
                                    String handle = ((String) identityMap.get(handleFieldId))
                                            .concat("@").concat(handleFieldId)
                                            .toLowerCase(Locale.ROOT);
                                    return new HandleDto(handle, getHandleHash(handle));
                                }));
            }
        }
        return Map.of();
    }

    public String getHandleHash(String handle) {
        //handle is converted to lowercase. It is language neutral conversion.
        int saltId = securityManager.getSaltKeyForHashOfId(handle.toLowerCase(Locale.ROOT));
        String salt = uinHashSaltRepo.retrieveSaltById(saltId);
        String saltedHash = securityManager.hashwithSalt(handle.getBytes(), CryptoUtil.decodePlainBase64(salt));
        return saltId + SPLITTER + saltedHash;
    }

    private List<String> getSupportedHandles(String schema) {
        List<String> supportedHandles = new ArrayList<>();
        List<String> paths = JsonPath.using(Configuration.builder().options(Option.AS_PATH_LIST).build())
                .parse(schema)
                .read("$['properties']['identity']['properties'][*][?(@['handle']==true)]");
        paths.forEach( path -> {
            // returns in below format, so need to remove parent paths
            //Eg: "$['properties']['identity']['properties']['phone']"
            supportedHandles.add(path.replace("$['properties']['identity']['properties']['", "")
                    .replace("']", ""));
        });
        return supportedHandles;
    }
}
