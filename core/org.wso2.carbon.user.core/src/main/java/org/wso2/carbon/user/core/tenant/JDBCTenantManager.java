/*
 *  Copyright (c) 2005-2008, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.user.core.tenant;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.common.RealmCache;
import org.wso2.carbon.user.core.common.UserStoreDeploymentManager;
import org.wso2.carbon.user.core.config.RealmConfigXMLProcessor;
import org.wso2.carbon.user.core.util.DatabaseUtil;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.DBUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

public class JDBCTenantManager implements TenantManager {
	DataSource dataSource;
	private static Log log = LogFactory.getLog(TenantManager.class);
	protected BundleContext bundleContext;

    /**
     * Map which maps tenant domains to tenant IDs
     *
     * Key - tenant domain, value - tenantId
     */
    private Map tenantDomainIdMap = new ConcurrentHashMap<String, Integer>();

    /**
     * This is the reverse of the tenantDomainIdMap. Key - tenantId, value - tenant domain
     */
    private Map tenantIdDomainMap = new ConcurrentHashMap<Integer, String>();

	protected TenantCache tenantCacheManager = TenantCache.getInstance();

	public JDBCTenantManager(OMElement omElement, Map<String, Object> properties) throws Exception {
        this.dataSource = (DataSource) properties.get(UserCoreConstants.DATA_SOURCE);
        if (dataSource == null) {
            throw new Exception("Data Source is null");
        }
        this.tenantCacheManager.clear();
    }

	//TODO : Remove the unused variable
	public JDBCTenantManager(DataSource dataSource, String superTenantDomain) {
		this.dataSource = dataSource;
	}

    public int addTenant(org.wso2.carbon.user.api.Tenant tenant) throws UserStoreException {
        // if tenant id present in tenant bean, we create the tenant with that tenant id.
        if (tenant.getId() > 0) {
            return addTenantWithGivenId(tenant);
        }

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        ResultSet result = null;
        int id = 0;
        try {
            dbConnection = getDBConnection();
            String sqlStmt = TenantConstants.ADD_TENANT_SQL;

            String dbProductName = dbConnection.getMetaData().getDatabaseProductName();
            prepStmt = dbConnection.prepareStatement(sqlStmt, new String[] { DBUtils
                    .getConvertedAutoGeneratedColumnName(dbProductName, "UM_ID") });
            prepStmt.setString(1, tenant.getDomain().toLowerCase());
            prepStmt.setString(2, tenant.getEmail());
            Date createdTime = tenant.getCreatedDate();
            long createdTimeMs;
            if (createdTime == null) {
                createdTimeMs = System.currentTimeMillis();
            } else {
                createdTimeMs = createdTime.getTime();
            }
            prepStmt.setTimestamp(3, new Timestamp(createdTimeMs));
            String realmConfigString = RealmConfigXMLProcessor.serialize(
                    (RealmConfiguration) tenant.getRealmConfig()).toString();
            InputStream is = new ByteArrayInputStream(realmConfigString.getBytes());
            prepStmt.setBinaryStream(4, is, is.available());

            prepStmt.executeUpdate();

            result = prepStmt.getGeneratedKeys();
            if (result.next()) {
                id = result.getInt(1);
            }
            dbConnection.commit();
        } catch (Exception e) {

            DatabaseUtil.rollBack(dbConnection);

            String msg = "Error in adding tenant with " + "tenant domain: " + tenant.getDomain().toLowerCase()
                    + ".";
            log.error(msg);
            throw new UserStoreException(e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
        }
        return id;
    }

    /**
     * This method is introduced when we require to create a tenant with provided tenant Id. In some cases, we need to
     * duplicate tenant in multiple environments with same Id.
     * @param tenant - tenant bean with tenantId set.
     * @return
     * @throws UserStoreException if tenant Id is already taken.
     */
    public int addTenantWithGivenId(org.wso2.carbon.user.api.Tenant tenant) throws UserStoreException {
        // check if tenant id is available, if not available throw exception.
        if (getTenant(tenant.getId()) != null) {
            String errorMsg = "Tenant with tenantId:" + tenant.getId() + " is already created. Tenant creation is " +
                    "aborted for tenant domain:" + tenant.getDomain();
            log.error(errorMsg);
            throw new UserStoreException(errorMsg);
        }

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        ResultSet result = null;
        int id = 0;
        try {
            dbConnection = getDBConnection();
            String sqlStmt = TenantConstants.ADD_TENANT_WITH_ID_SQL;

            String dbProductName = dbConnection.getMetaData().getDatabaseProductName();
            prepStmt = dbConnection.prepareStatement(sqlStmt, new String[] { DBUtils
                    .getConvertedAutoGeneratedColumnName(dbProductName, "UM_ID") });
            prepStmt.setInt(1, tenant.getId());
            prepStmt.setString(2, tenant.getDomain().toLowerCase());
            prepStmt.setString(3, tenant.getEmail());
            Date createdTime = tenant.getCreatedDate();
            long createdTimeMs;
            if (createdTime == null) {
                createdTimeMs = System.currentTimeMillis();
            } else {
                createdTimeMs = createdTime.getTime();
            }
            prepStmt.setTimestamp(4, new Timestamp(createdTimeMs));
            String realmConfigString = RealmConfigXMLProcessor.serialize(
                    (RealmConfiguration) tenant.getRealmConfig()).toString();
            InputStream is = new ByteArrayInputStream(realmConfigString.getBytes());
            prepStmt.setBinaryStream(5, is, is.available());

            prepStmt.executeUpdate();

            id = tenant.getId();
            dbConnection.commit();
        } catch (Exception e) {

            DatabaseUtil.rollBack(dbConnection);

            String msg = "Error in adding tenant with " + "tenant domain: " + tenant.getDomain().toLowerCase()
                    + ".";
            log.error(msg);
            throw new UserStoreException(e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
        }
        return id;
    }

 
    public void updateTenant(org.wso2.carbon.user.api.Tenant tenant) throws UserStoreException {
	    tenantCacheManager.clearCacheEntry(new TenantIdKey(tenant.getId()));
		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.UPDATE_TENANT_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setString(1, tenant.getDomain().toLowerCase());
			prepStmt.setString(2, tenant.getEmail());
			Date createdTime = tenant.getCreatedDate();
			long createdTimeMs;
			if (createdTime == null) {
				createdTimeMs = System.currentTimeMillis();
			} else {
				createdTimeMs = createdTime.getTime();
			}
			prepStmt.setTimestamp(3, new Timestamp(createdTimeMs));
			prepStmt.setInt(4, tenant.getId());

			prepStmt.executeUpdate();

			dbConnection.commit();
		} catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);
            
			String msg = "Error in updating tenant with " + "tenant domain: "
					+ tenant.getDomain().toLowerCase() + ".";
			log.error(msg);
			throw new UserStoreException(e);
		} finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
		}
	}

    public void updateTenantRealmConfig(org.wso2.carbon.user.api.Tenant tenant) throws UserStoreException {

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            String sqlStmt;
            String realmConfigString = null;

            if(tenant.getRealmConfig() != null){
                realmConfigString = RealmConfigXMLProcessor.serialize(tenant.getRealmConfig()).toString();
                if(realmConfigString != null && realmConfigString.trim().length() > 0 ){
                    sqlStmt = TenantConstants.UPDATE_TENANT_CONFIG_SQL;
                    prepStmt = dbConnection.prepareStatement(sqlStmt);
                    InputStream is = null;                    
                    try{
                        is = new ByteArrayInputStream(realmConfigString.getBytes());
                        prepStmt.setBinaryStream(1, is, is.available());
                        prepStmt.setInt(2, tenant.getId());
                        prepStmt.executeUpdate();
                        dbConnection.commit();
                        tenantCacheManager.clearCacheEntry(new TenantIdKey(tenant.getId()));
                        RealmCache.getInstance().clearFromCache(tenant.getId(), "primary");                        
                    } catch (IOException e){
                        log.error("Error occurs while reading realm configuration", e);
                    } finally {
                        if(is != null){
                            try {
                                is.close();
                            } catch (IOException e) {
                                log.error(e);
                            }
                        }
                    }
                }
            }

        } catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);

            String msg = "Error in updating tenant realm configuration with " + "tenant domain: "
                    + tenant.getDomain().toLowerCase() + ".";
            log.error(msg);
            throw new UserStoreException(e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    public Tenant getTenant(int tenantId) throws UserStoreException {
	    
        @SuppressWarnings("unchecked")
        TenantCacheEntry<Tenant> entry = (TenantCacheEntry<Tenant>) tenantCacheManager
                .getValueFromCache(new TenantIdKey(tenantId));
        if ((entry != null) && (entry.getTenant() != null)) {
            return entry.getTenant();
        }
		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		ResultSet result = null;
		Tenant tenant = null;
		int id;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.GET_TENANT_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setInt(1, tenantId);

			result = prepStmt.executeQuery();

			if (result.next()) {
				id = result.getInt("UM_ID");
				String domain = result.getString("UM_DOMAIN_NAME");
				String email = result.getString("UM_EMAIL");
				boolean active = result.getBoolean("UM_ACTIVE");
				Date createdDate = new Date(result.getTimestamp(
						"UM_CREATED_DATE").getTime());
				InputStream is = result.getBinaryStream("UM_USER_CONFIG");
							    
			    RealmConfigXMLProcessor processor = new RealmConfigXMLProcessor();
			    RealmConfiguration realmConfig = processor.buildTenantRealmConfiguration(is);
			    realmConfig.setTenantId(id);
			    
				tenant = new Tenant();
				tenant.setId(id);
				tenant.setDomain(domain);
				tenant.setEmail(email);
				tenant.setCreatedDate(createdDate);
				tenant.setActive(active);
				tenant.setRealmConfig(realmConfig);
                setSecondaryUserStoreConfig(realmConfig, tenantId);
				tenant.setAdminName(realmConfig.getAdminUserName());
				tenantCacheManager.addToCache(new TenantIdKey(id), new TenantCacheEntry<Tenant>(tenant));
			}
			dbConnection.commit();
        } catch (SQLException e) {
            DatabaseUtil.rollBack(dbConnection);
            String msg = "Error in getting the tenant with " + "tenant id: " + tenantId + ".";
            log.error(msg);
            throw new UserStoreException(e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
        }
		return tenant;
	}

	/**
	 * TODO : Introduce DTOs
	 */
    public Tenant[] getAllTenants() throws UserStoreException {
		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		ResultSet result = null;
		List<Tenant> tenantList = new ArrayList<Tenant>();
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.GET_ALL_TENANTS_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);

			result = prepStmt.executeQuery();

			while (result.next()) {
				int id = result.getInt("UM_ID");
				String domain = result.getString("UM_DOMAIN_NAME");
				String email = result.getString("UM_EMAIL");
				boolean active = result.getBoolean("UM_ACTIVE");
				Date createdDate = new Date(result.getTimestamp(
						"UM_CREATED_DATE").getTime());

				Tenant tenant = new Tenant();
				tenant.setId(id);
				tenant.setDomain(domain);
				tenant.setEmail(email);
				tenant.setActive(active);
				tenant.setCreatedDate(createdDate);
				tenantList.add(tenant);
			}
			dbConnection.commit();
		} catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);
            
			String msg = "Error in getting the tenants.";
			log.error(msg);
			throw new UserStoreException(e);
		} finally {
            DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
		}
		return tenantList.toArray(new Tenant[tenantList.size()]);
	}

    public String getDomain(int tenantId) throws UserStoreException {
		if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
			return MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
		} else if (tenantId == MultitenantConstants.INVALID_TENANT_ID) {
			return null;
		}

        String tenantDomain = (String) tenantIdDomainMap.get(tenantId);
        if(tenantDomain != null){
            return tenantDomain;
        }
        
		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		ResultSet result = null;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.GET_DOMAIN_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setInt(1, tenantId);

			result = prepStmt.executeQuery();

			if (result.next()) {
				tenantDomain = result.getString("UM_DOMAIN_NAME");
			}
			dbConnection.commit();
		} catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);

			String msg = "Error in getting the tenant with " + "tenant id: "
					+ tenantId + ".";
			log.error(msg);
			throw new UserStoreException(e);
		} finally {
			DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
		}
		
		if(tenantDomain != null && !tenantDomain.isEmpty() && 
				tenantId != MultitenantConstants.INVALID_TENANT_ID) {
            tenantIdDomainMap.put(tenantId, tenantDomain);
        }
        
		return tenantDomain;
	}

    public Tenant[] getAllTenantsForTenantDomainStr(String tenantDomain) throws UserStoreException {
		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		ResultSet result = null;
		List<Tenant> tenantList = new ArrayList<Tenant>();
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.GET_MATCHING_TENANT_IDS_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setString(1, "%"+tenantDomain.toLowerCase()+"%");

			result = prepStmt.executeQuery();

			while (result.next()) {
				int id = result.getInt("UM_ID");
				String domain = result.getString("UM_DOMAIN_NAME");
				String email = result.getString("UM_EMAIL");
				boolean active = result.getBoolean("UM_ACTIVE");
				Date createdDate = new Date(result.getTimestamp(
						"UM_CREATED_DATE").getTime());

				Tenant tenant = new Tenant();
				tenant.setId(id);
				tenant.setDomain(domain);
				tenant.setEmail(email);
				tenant.setActive(active);
				tenant.setCreatedDate(createdDate);
				tenantList.add(tenant);
			}
			dbConnection.commit();
		} catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);
            
			String msg = "Error in getting the tenants.";
			log.error(msg);
			throw new UserStoreException(e);
		} finally {
            DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
		}
		return tenantList.toArray(new Tenant[tenantList.size()]);
	}
    
    public int getTenantId(String tenantDomain) throws UserStoreException {
        if(tenantDomain != null){
            tenantDomain = tenantDomain.toLowerCase();
        }

		if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
			return MultitenantConstants.SUPER_TENANT_ID;
		} else if (tenantDomain == null) {
			return MultitenantConstants.INVALID_TENANT_ID;
		}
        Integer tenantId = (Integer) tenantDomainIdMap.get(tenantDomain);
        if(tenantId != null) {
            return tenantId;
        }

        Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		ResultSet result = null;
		tenantId = MultitenantConstants.INVALID_TENANT_ID;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.GET_TENANT_ID_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setString(1, tenantDomain);

			result = prepStmt.executeQuery();

			if (result.next()) {
				tenantId = result.getInt("UM_ID");
			}
			dbConnection.commit();
            if (tenantDomain != null && !tenantDomain.isEmpty() && 
            		tenantId != MultitenantConstants.INVALID_TENANT_ID ) {
                tenantDomainIdMap.put(tenantDomain, tenantId);
            }
        } catch (SQLException e) {
            DatabaseUtil.rollBack(dbConnection);
			String msg = "Error in getting the tenant id with tenant domain: " + tenantDomain + ".";
			log.error(msg, e);
			throw new UserStoreException(msg, e);
		} finally {
            DatabaseUtil.closeAllConnections(dbConnection, result, prepStmt);
		}
		return tenantId;
	}

    public void activateTenant(int tenantId) throws UserStoreException {
    	    	
        tenantCacheManager.clearCacheEntry(new TenantIdKey(tenantId));

		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.ACTIVATE_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setInt(1, tenantId);
			prepStmt.executeUpdate();
			dbConnection.commit();
		} catch (SQLException e) {
            DatabaseUtil.rollBack(dbConnection);
			String msg = "Error in activating the tenant with " + "tenant id: "
					+ tenantId + ".";
			log.error(msg, e);
			throw new UserStoreException(msg, e);
		} finally {
			DatabaseUtil.closeAllConnections(dbConnection,prepStmt);
		}
	}

    public void deactivateTenant(int tenantId) throws UserStoreException {

        // Remove tenant information from the cache.
        tenantIdDomainMap.remove(tenantId);
        tenantCacheManager.clearCacheEntry(new TenantIdKey(tenantId));

		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.DEACTIVATE_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setInt(1, tenantId);
			prepStmt.executeUpdate();
			dbConnection.commit();
		} catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);

			String msg = "Error in deactivating the tenant with " + "tenant id: "
					+ tenantId + ".";
			log.error(msg);
			throw new UserStoreException(e);
		} finally {
			DatabaseUtil.closeAllConnections(dbConnection,prepStmt);
		}
	}

    public boolean isTenantActive(int tenantId) throws UserStoreException {
		if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
			return true;
		}
		Connection dbConnection = null;
		PreparedStatement prepStmt = null;
		try {
			dbConnection = getDBConnection();
			String sqlStmt = TenantConstants.IS_TENANT_ACTIVE_SQL;
			prepStmt = dbConnection.prepareStatement(sqlStmt);
			prepStmt.setInt(1, tenantId);
			ResultSet result = prepStmt.executeQuery();
			if (result.next()) {
				return result.getBoolean("UM_ACTIVE");				
			}
			dbConnection.commit();
		} catch (SQLException e) {

            DatabaseUtil.rollBack(dbConnection);

			String msg = "Error in getting the tenant status with " + "tenant id: "
					+ tenantId + ".";
			log.error(msg);
			throw new UserStoreException(e);
		} finally {
			DatabaseUtil.closeAllConnections(dbConnection,prepStmt);
		}
		return false;
	}

    /**
     * Delete Tenant
     *
     * @param tenantId
     *            - Tenant Id
     * @throws UserStoreException
     */
    public void deleteTenant(int tenantId) throws UserStoreException {
        try {
            deleteTenant(tenantId, true);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException(e);
        }
    }

    /**
     * Delete Tenant
     *
     * @param tenantId
     *            - Tenant Id
     * @param removeFromPersistentStorage
     *            - Flag to decide weather delete from persistent storage
     * @throws UserStoreException
     */
    public void deleteTenant(int tenantId, boolean removeFromPersistentStorage)
            throws org.wso2.carbon.user.api.UserStoreException {
        // Remove tenant information from the cache.
        getDomain(tenantId); //fill the tenantIdDomainMap
        String tenantDomain = (String) tenantIdDomainMap.remove(tenantId);
        if (tenantDomain != null) {
            tenantDomainIdMap.remove(tenantDomain);
        }
        tenantCacheManager.clearCacheEntry(new TenantIdKey(tenantId));

        if (removeFromPersistentStorage) {
            Connection dbConnection = null;
            PreparedStatement prepStmt = null;
            try {
                dbConnection = getDBConnection();
                String sqlStmt = TenantConstants.DELETE_TENANT_SQL;
                prepStmt = dbConnection.prepareStatement(sqlStmt);
                prepStmt.setInt(1, tenantId);

                prepStmt.executeUpdate();
                dbConnection.commit();
            } catch (SQLException e) {
                DatabaseUtil.rollBack(dbConnection);
                String msg = "Error in deleting the tenant with "
                        + "tenant id: " + tenantId + ".";
                log.error(msg, e);
                throw new UserStoreException(msg, e);
            } finally {
                DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
            }
        }
    }

    public void setBundleContext(BundleContext bundleContext) {
		this.bundleContext = bundleContext;
	}

    /**
     * @inheritDoc
     */
    public void initializeExistingPartitions() {
        //this method needs not to be implemented in tenant management with JDBC.
    }

    private Connection getDBConnection() throws SQLException {
		Connection dbConnection = DatabaseUtil.getDBConnection(this.dataSource);
		dbConnection.setAutoCommit(false);
		return dbConnection;
	}

    public String getSuperTenantDomain() throws UserStoreException {
		return MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
	}
    
    /**
     * Read in the secondary user store configurations if available
     *
     * @param realmConfiguration  <code>RealmConfiguration</code>
     * @param tenantId tenant id
     * @throws UserStoreException throws
     */
    private void setSecondaryUserStoreConfig(RealmConfiguration realmConfiguration, int tenantId)
                                                                        throws UserStoreException {
        // Get the last realm configuration
    	RealmConfiguration lastRealm = realmConfiguration;
    	if(realmConfiguration != null) {
    		while(lastRealm.getSecondaryRealmConfig() != null) {
    			lastRealm = lastRealm.getSecondaryRealmConfig();
    		}
    	
	    	String configPath = CarbonUtils.getCarbonTenantsDirPath() +
                    File.separator + tenantId + File.separator + "userstores";
	        File userStores = new File(configPath);
	        UserStoreDeploymentManager userStoreDeploymentManager = new UserStoreDeploymentManager();
	
	        File[] files = userStores.listFiles(new FilenameFilter() {
	            public boolean accept(File userStores, String name) {
	                return name.toLowerCase().endsWith(".xml");
	            }
	        });
	        if (files != null) {
	            for (File file : files) {
	            	RealmConfiguration newRealmConfig = userStoreDeploymentManager.
                                                getUserStoreConfiguration(file.getAbsolutePath());
		            if(newRealmConfig != null) {
		            	lastRealm.setSecondaryRealmConfig(newRealmConfig);
		            	lastRealm = lastRealm.getSecondaryRealmConfig();
	            	}
		            else {
		            	log.error("Error while creating realm configuration from file " + file.getAbsolutePath());
		            }
	            }
	        }        
    	}

    }
}