package org.cloudname;

import java.util.List;

/**
 * The service handle -- the interface through which services
 * communicate their state to the outside world and where services can
 * register listeners to handle configuration updates.
 *
 * @author borud
 */
public interface ServiceHandle {
    /**
     * Set the status of this service.
     *
     * @param status the new status.
     */
    public void setStatus(ServiceStatus status);

    /**
     * Publish a named endpoint.  If the endpoint already exists an
     * exception will be thrown.  If you want to republish a named
     * endpoint you first have to remove it.
     *
     * @param endpoint the endpoint data.
     */
    public void putEndpoint(Endpoint endpoint);


    public void putEndpoints(List<Endpoint> endpoints);

    /**
     * Remove a published endpoint.
     *
     * @param name the name of the endpoint we wish to remove.
     */
    public void removeEndpoint(String name);
    public void removeEndpoints(List<String> names);

    /**
     * Register a ConfigListener which will be called whenever there
     * is a configuration change.
     */
    public void registerConfigListener(ConfigListener listener);
    
    /**
     * Close the service handle and free up the coordinate so it can
     * be claimed by others.  After close() has been called all
     * operations on this instance of the service handle will result
     * in an exception being thrown. All endpoints are deleted.
     */
    public void close();
}

