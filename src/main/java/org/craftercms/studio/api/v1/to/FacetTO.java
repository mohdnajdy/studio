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

package org.craftercms.studio.api.v1.to;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

/**
 * Holds the data for a search facet
 * @author joseross
 */
public class FacetTO {

    /**
     * The name of the facet
     */
    protected String name;

    /**
     * The name of the field
     */
    protected String field;

    /**
     * The ranges of the facet
     */
    protected List<FacetRangeTO> ranges;

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getField() {
        return field;
    }

    public void setField(final String field) {
        this.field = field;
    }

    public List<FacetRangeTO> getRanges() {
        return ranges;
    }

    public void setRanges(final List<FacetRangeTO> ranges) {
        this.ranges = ranges;
    }

    public boolean isRange() {
        return CollectionUtils.isNotEmpty(ranges);
    }

}
