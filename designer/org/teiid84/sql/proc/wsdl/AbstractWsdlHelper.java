/*
 * JBoss, Home of Professional Open Source.
*
* See the LEGAL.txt file distributed with this work for information regarding copyright ownership and licensing.
*
* See the AUTHORS.txt file distributed with this work for a full listing of individual contributors.
*/
package org.teiid84.sql.proc.wsdl;

import org.teiid.language.SQLConstants;

/**
 *
 */
public abstract class AbstractWsdlHelper {

    /**
     * 
     */
    public AbstractWsdlHelper() {
        super();
    }

    /**
     * Converts any name string to a valid SQL symbol segment
     * Basically looks to see if name is a reserved word and if so, returns the name in double-quotes
     * 
     * @param name
     * @return
     */
    protected String convertSqlNameSegment(String name) {       
        if( SQLConstants.isReservedWord(name) ) {
            return '\"' + name + '\"';
        }
        
        return name;
    }

}
