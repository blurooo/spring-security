/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.web;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.web.util.AnyRequestMatcher;
import org.springframework.security.web.util.RequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;


/**
 * Delegates <code>Filter</code> requests to a list of Spring-managed beans.
 * As of version 2.0, you shouldn't need to explicitly configure a <tt>FilterChainProxy</tt> bean in your application
 * context unless you need very fine control over the filter chain contents. Most cases should be adequately covered
 * by the default <tt>&lt;security:http /&gt;</tt> namespace configuration options.
 * <p>
 * The <code>FilterChainProxy</code> is loaded via a standard Spring {@link DelegatingFilterProxy} declaration in
 * <code>web.xml</code>.
 * <p>
 * As of version 3.1, <tt>FilterChainProxy</tt> is configured using an ordered Map of {@link RequestMatcher} instances
 * to <tt>List</tt>s of <tt>Filter</tt>s. The Map instance will normally be created while parsing the namespace
 * configuration, so doesn't have to be set explicitly. Instead the &lt;filter-chain-map&gt; element should be used
 * within the FilterChainProxy bean declaration.
 * This in turn should have a list of child &lt;filter-chain&gt; elements which each define a URI pattern and the list
 * of filters (as comma-separated bean names) which should be applied to requests which match the pattern.
 * An example configuration might look like this:
 *
 * <pre>
 &lt;bean id="myfilterChainProxy" class="org.springframework.security.util.FilterChainProxy">
     &lt;security:filter-chain-map request-matcher="ant">
         &lt;security:filter-chain pattern="/do/not/filter" filters="none"/>
         &lt;security:filter-chain pattern="/**" filters="filter1,filter2,filter3"/>
     &lt;/security:filter-chain-map>
 &lt;/bean>
 * </pre>
 *
 * The names "filter1", "filter2", "filter3" should be the bean names of <tt>Filter</tt> instances defined in the
 * application context. The order of the names defines the order in which the filters will be applied. As shown above,
 * use of the value "none" for the "filters" can be used to exclude a request pattern from the security filter chain
 * entirely. Please consult the security namespace schema file for a full list of available configuration options.
 * <p>
 * Each possible pattern that <code>FilterChainProxy</code> should service must be entered.
 * The first match for a given request will be used to define all of the <code>Filter</code>s that apply to that
 * request. This means you must put most specific matches at the top of the list, and ensure all <code>Filter</code>s
 * that should apply for a given matcher are entered against the respective entry.
 * The <code>FilterChainProxy</code> will not iterate through the remainder of the map entries to locate additional
 * <code>Filter</code>s.
 * <p>
 * <code>FilterChainProxy</code> respects normal handling of <code>Filter</code>s that elect not to call {@link
 * javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse,
 * javax.servlet.FilterChain)}, in that the remainder of the original or <code>FilterChainProxy</code>-declared filter
 * chain will not be called.
 * <p>
 * Note the <code>Filter</code> lifecycle mismatch between the servlet container and IoC
 * container. As described in the {@link DelegatingFilterProxy} JavaDocs, we recommend you allow the IoC
 * container to manage the lifecycle instead of the servlet container.
 *
 * @author Carlos Sanchez
 * @author Ben Alex
 * @author Luke Taylor
 *
 */
public class FilterChainProxy extends GenericFilterBean {
    //~ Static fields/initializers =====================================================================================

    private static final Log logger = LogFactory.getLog(FilterChainProxy.class);

    //~ Instance fields ================================================================================================

    private Map<RequestMatcher, List<Filter>> filterChainMap;

    private FilterChainValidator filterChainValidator = new NullFilterChainValidator();

    //~ Methods ========================================================================================================

    @Override
    public void afterPropertiesSet() {
        Assert.notNull(filterChainMap, "filterChainMap must be set");
        filterChainValidator.validate(this);
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        FilterInvocation fi = new FilterInvocation(request, response, chain);
        List<Filter> filters = getFilters(fi.getRequest());

        if (filters == null || filters.size() == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug(fi.getRequestUrl() +
                        (filters == null ? " has no matching filters" : " has an empty filter list"));
            }

            chain.doFilter(request, response);

            return;
        }

        VirtualFilterChain virtualFilterChain = new VirtualFilterChain(fi, filters);
        virtualFilterChain.doFilter(fi.getRequest(), fi.getResponse());
    }


    /**
     * Returns the first filter chain matching the supplied URL.
     *
     * @param request the request to match
     * @return an ordered array of Filters defining the filter chain
     */
    private List<Filter> getFilters(HttpServletRequest request)  {
        for (Map.Entry<RequestMatcher, List<Filter>> entry : filterChainMap.entrySet()) {
            RequestMatcher matcher = entry.getKey();

            if (matcher.matches(request)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Convenience method, mainly for testing.
     *
     * @param url the URL
     * @return matching filter list
     */
    public List<Filter> getFilters(String url) {
        return getFilters(new FilterInvocation(url, null).getRequest());
    }

    /**
     * Sets the mapping of URL patterns to filter chains.
     *
     * The map keys should be the paths and the values should be arrays of <tt>Filter</tt> objects.
     * It's VERY important that the type of map used preserves ordering - the order in which the iterator
     * returns the entries must be the same as the order they were added to the map, otherwise you have no way
     * of guaranteeing that the most specific patterns are returned before the more general ones. So make sure
     * the Map used is an instance of <tt>LinkedHashMap</tt> or an equivalent, rather than a plain <tt>HashMap</tt>, for
     * example.
     *
     * @param filterChainMap the map of path Strings to <tt>List&lt;Filter&gt;</tt>s.
     */
    @SuppressWarnings("unchecked")
    public void setFilterChainMap(Map filterChainMap) {
        checkContents(filterChainMap);
        this.filterChainMap = new LinkedHashMap<RequestMatcher, List<Filter>>(filterChainMap);
        checkPathOrder();
    }

    @SuppressWarnings("unchecked")
    private void checkContents(Map filterChainMap) {
        for (Object key : filterChainMap.keySet()) {
            Assert.isInstanceOf(RequestMatcher.class, key, "Path key must be a RequestMatcher but found " + key);
            Object filters = filterChainMap.get(key);
            Assert.isInstanceOf(List.class, filters, "Value must be a filter list");
            // Check the contents

            for (Object filter : ((List) filters)) {
                Assert.isInstanceOf(Filter.class, filter, "Objects in filter chain must be of type Filter. ");
            }
        }
    }

    private void checkPathOrder() {
        // Check that the universal pattern is listed at the end, if at all
        Iterator<RequestMatcher> matchers = filterChainMap.keySet().iterator();

        while(matchers.hasNext()) {
            if ((matchers.next() instanceof AnyRequestMatcher && matchers.hasNext())) {
                throw new IllegalArgumentException("A universal match pattern ('/**') is defined " +
                        " before other patterns in the filter chain, causing them to be ignored. Please check the " +
                        "ordering in your <security:http> namespace or FilterChainProxy bean configuration");
            }
        }
    }

    /**
     * Returns a copy of the underlying filter chain map. Modifications to the map contents
     * will not affect the FilterChainProxy state - to change the map call <tt>setFilterChainMap</tt>.
     *
     * @return the map of path pattern Strings to filter chain lists (with ordering guaranteed).
     */
    public Map<RequestMatcher, List<Filter>> getFilterChainMap() {
        return new LinkedHashMap<RequestMatcher, List<Filter>>(filterChainMap);
    }

    /**
     * Used (internally) to specify a validation strategy for the filters in each configured chain.
     *
     * @param filterChainValidator the validator instance which will be invoked on during initialization
     * to check the {@code FilterChainProxy} instance.
     */
    public void setFilterChainValidator(FilterChainValidator filterChainValidator) {
        this.filterChainValidator = filterChainValidator;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FilterChainProxy[");
        sb.append("Filter Chains: ");
        sb.append(filterChainMap);
        sb.append("]");

        return sb.toString();
    }

    //~ Inner Classes ==================================================================================================

    /**
     * Internal {@code FilterChain} implementation that is used to pass a request through the additional
     * internal list of filters which match the request. Records the position in the additional filter chain and, when
     * completed, passes the request back to the original {@code FilterChain} supplied by the servlet container.
     */
    private static class VirtualFilterChain implements FilterChain {
        private final FilterInvocation fi;
        private final List<Filter> additionalFilters;
        private final int size;
        private int currentPosition = 0;


        private VirtualFilterChain(FilterInvocation filterInvocation, List<Filter> additionalFilters) {
            this.fi = filterInvocation;
            this.additionalFilters = additionalFilters;
            this.size = additionalFilters.size();
        }

        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            if (currentPosition == size) {
                if (logger.isDebugEnabled()) {
                    logger.debug(fi.getRequestUrl()
                        + " reached end of additional filter chain; proceeding with original chain");
                }

                fi.getChain().doFilter(request, response);
            } else {
                currentPosition++;

                Filter nextFilter = additionalFilters.get(currentPosition - 1);

                if (logger.isDebugEnabled()) {
                    logger.debug(fi.getRequestUrl() + " at position " + currentPosition + " of "
                        + size + " in additional filter chain; firing Filter: '"
                        + nextFilter + "'");
                }

               nextFilter.doFilter(request, response, this);
            }
        }
    }

    public interface FilterChainValidator {
        void validate(FilterChainProxy filterChainProxy);
    }

    private class NullFilterChainValidator implements FilterChainValidator {
        public void validate(FilterChainProxy filterChainProxy) {
        }
    }

}