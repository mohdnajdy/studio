/*
 * Copyright (C) 2007-2019 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.craftercms.studio.api.v2.service.audit;

import org.craftercms.studio.api.v1.exception.ServiceLayerException;
import org.craftercms.studio.api.v1.exception.SiteNotFoundException;
import org.craftercms.studio.api.v1.to.ContentItemTO;
import org.craftercms.studio.api.v2.dal.AuditLog;

import java.time.ZonedDateTime;
import java.util.List;

public interface AuditService {

    /**
     * Get audit log for site
     *
     * @param site site
     * @param offset offset of the first record
     * @param limit number of records to return
     * @param user filter logs by user
     * @param actions filter logs by actions
     * @return audit list
     * @throws SiteNotFoundException thrown if site does not exist
     */
    List<AuditLog> getAuditLogForSite(String site, int offset, int limit, String user, List<String> actions)
            throws SiteNotFoundException;

    /**
     * Get total number of audit log entries for site
     *
     * @param site site
     * @param user filter logs by user
     * @param actions filter logs by actions
     * @return number of audit log entries
     * @throws SiteNotFoundException thrown if site does not exist
     */
    int getAuditLogForSiteTotal(String site, String user, List<String> actions) throws SiteNotFoundException;

    List<AuditLog> getAuditLog(String siteId, String siteName, int offset, int limit, String user,
                               List<String> operations, boolean includeParameters, ZonedDateTime dateFrom,
                               ZonedDateTime dateTo, String target, String origin, String clusterNodeId, String sort,
                               String order);

    int getAuditLogTotal(String siteId, String siteName, String user, List<String> operations,
                                    boolean includeParameters, ZonedDateTime dateFrom, ZonedDateTime dateTo,
                                    String target, String origin, String clusterNodeId);

    /**
     * Get audit log entry by id
     *
     * @param auditLogId audit log id
     * @return audit log entry
     */
    AuditLog getAuditLogEntry(long auditLogId);

    /**
     * Get user activities
     *
     * @param site site
     * @param user username
     * @param limit limit
     * @param sort sort by
     * @param ascending true if ascending order, otherwise false
     * @param excludeLive exclude live items
     * @param filterType filter type
     * @return list of content items
     * @throws ServiceLayerException
     */
    List<ContentItemTO> getUserActivities(String site, String user, int limit, String sort, boolean ascending,
                                      boolean excludeLive, String filterType) throws ServiceLayerException;
}
