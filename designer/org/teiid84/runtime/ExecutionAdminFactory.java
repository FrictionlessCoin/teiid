/*
 * JBoss, Home of Professional Open Source.
*
* See the LEGAL.txt file distributed with this work for information regarding copyright ownership and licensing.
*
* See the AUTHORS.txt file distributed with this work for a full listing of individual contributors.
*/
package org.teiid84.runtime;

import java.sql.Driver;
import org.teiid.designer.query.IQueryService;
import org.teiid.designer.runtime.spi.IExecutionAdmin;
import org.teiid.designer.runtime.spi.IExecutionAdminFactory;
import org.teiid.designer.runtime.spi.ITeiidServer;
import org.teiid.designer.type.IDataTypeManagerService;
import org.teiid.jdbc.TeiidDriver;
import org.teiid84.sql.QueryService;
import org.teiid84.type.DataTypeManagerService;

/**
 *
 */
public class ExecutionAdminFactory implements IExecutionAdminFactory {

    private DataTypeManagerService dataTypeManagerService;
    
    private QueryService queryService;

    @Override
    public IExecutionAdmin createExecutionAdmin(ITeiidServer teiidServer) throws Exception {
        return new ExecutionAdmin(teiidServer);
    }
    
    @Override
    public IDataTypeManagerService getDataTypeManagerService() {
        if (dataTypeManagerService == null) {
            dataTypeManagerService = new DataTypeManagerService();
        }
        
        return dataTypeManagerService;
    }

    @Override
    public Driver getTeiidDriver() {
        return TeiidDriver.getInstance();
    }

    @Override
    public IQueryService getQueryService() {
        if (queryService == null) {
            queryService = new QueryService();
        }
        
        return queryService;
    }
}
