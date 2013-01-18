/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.processor.relational;

import static org.teiid.query.analysis.AnalysisRecord.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.teiid.api.exception.query.ExpressionEvaluationException;
import org.teiid.client.plan.PlanNode;
import org.teiid.common.buffer.BlockedException;
import org.teiid.common.buffer.BufferManager;
import org.teiid.common.buffer.TupleBatch;
import org.teiid.common.buffer.TupleBuffer;
import org.teiid.common.buffer.TupleSource;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidProcessingException;
import org.teiid.core.types.DataTypeManager;
import org.teiid.language.SortSpecification.NullOrdering;
import org.teiid.query.eval.Evaluator;
import org.teiid.query.function.aggregate.*;
import org.teiid.query.processor.BatchCollector;
import org.teiid.query.processor.BatchCollector.BatchProducer;
import org.teiid.query.processor.ProcessorDataManager;
import org.teiid.query.processor.relational.SortUtility.Mode;
import org.teiid.query.sql.LanguageObject;
import org.teiid.query.sql.lang.OrderBy;
import org.teiid.query.sql.lang.OrderByItem;
import org.teiid.query.sql.symbol.AggregateSymbol;
import org.teiid.query.sql.symbol.AggregateSymbol.Type;
import org.teiid.query.sql.symbol.ElementSymbol;
import org.teiid.query.sql.symbol.Expression;
import org.teiid.query.sql.symbol.TextLine;
import org.teiid.query.sql.util.SymbolMap;
import org.teiid.query.util.CommandContext;


public class GroupingNode extends SubqueryAwareRelationalNode {

    static class ProjectingTupleSource extends
			BatchCollector.BatchProducerTupleSource {
    	
    	private Evaluator eval;
    	private List<Expression> collectedExpressions;
    	
		ProjectingTupleSource(BatchProducer sourceNode, Evaluator eval, List<Expression> expressions) {
			super(sourceNode);
			this.eval = eval;
			this.collectedExpressions = expressions;
		}

		@Override
		protected List<Object> updateTuple(List<?> tuple) throws ExpressionEvaluationException, BlockedException, TeiidComponentException {
			int columns = collectedExpressions.size();
		    List<Object> exprTuple = new ArrayList<Object>(columns);
		    for(int col = 0; col<columns; col++) { 
		        // The following call may throw BlockedException, but all state to this point
		        // is saved in class variables so we can start over on building this tuple
		        Object value = eval.evaluate(collectedExpressions.get(col), tuple);
		        exprTuple.add(value);
		    }
		    return exprTuple;
		}
	}

	// Grouping columns set by the planner 
	private List<OrderByItem> orderBy;
	private boolean removeDuplicates;
	private SymbolMap outputMapping;
    
    // Collection phase
    private int phase = COLLECTION;
    private Map elementMap;                    // Map of incoming symbol to index in source elements
    private LinkedHashMap<Expression, Integer> collectedExpressions;         // Collected Expressions
    private int distinctCols = -1;
       
    // Sort phase
    private SortUtility sortUtility;
    private TupleBuffer sortBuffer;
    private TupleSource groupTupleSource;
    
    // Group phase
    private AggregateFunction[] functions;
    private List<?> lastRow;
	private List<?> currentGroupTuple;

    private static final int COLLECTION = 1;
    private static final int SORT = 2;
    private static final int GROUP = 3;
	private int[] indexes;

	public GroupingNode(int nodeID) {
		super(nodeID);
	}

    public void reset() {
        super.reset();

        phase = COLLECTION;
                
        sortUtility = null;
        sortBuffer = null;
        
        lastRow = null;
        currentGroupTuple = null;
        
        if (this.functions != null) {
	    	for (AggregateFunction function : this.functions) {
				function.reset();
			}
        }
    }
    
    public void setRemoveDuplicates(boolean removeDuplicates) {
		this.removeDuplicates = removeDuplicates;
	}

    public void setOrderBy(List<OrderByItem> orderBy) {
		this.orderBy = orderBy;
	}
    
    public void setOutputMapping(SymbolMap outputMapping) {
		this.outputMapping = outputMapping;
	}

	@Override
	public void initialize(CommandContext context, BufferManager bufferManager,
			ProcessorDataManager dataMgr) {
		super.initialize(context, bufferManager, dataMgr);
		
		if (this.functions != null) {
			return;
		}
		
        // Incoming elements and lookup map for evaluating expressions
        List<? extends Expression> sourceElements = this.getChildren()[0].getElements();
        this.elementMap = createLookupMap(sourceElements);
        this.collectedExpressions = new LinkedHashMap<Expression, Integer>();
    	// List should contain all grouping columns / expressions as we need those for sorting
        if(this.orderBy != null) {
            for (OrderByItem item : this.orderBy) {
            	Expression ex = SymbolMap.getExpression(item.getSymbol());
            	getIndex(ex, this.collectedExpressions);
			}
            if (removeDuplicates) {
            	for (Expression ses : sourceElements) {
            		getIndex(ses, collectedExpressions);
            	}
            	distinctCols = collectedExpressions.size();
            }
        }
        
        // Construct aggregate function state accumulators
        functions = new AggregateFunction[getElements().size()];
        for(int i=0; i<getElements().size(); i++) {
            Expression symbol = getElements().get(i);
            if (this.outputMapping != null) {
            	symbol = outputMapping.getMappedExpression((ElementSymbol)symbol);
            }
            Class<?> outputType = symbol.getType();
            if(symbol instanceof AggregateSymbol) {
            	AggregateSymbol aggSymbol = (AggregateSymbol) symbol;
            	functions[i] = initAccumulator(aggSymbol, this, this.collectedExpressions);
            } else {
                functions[i] = new ConstantFunction();
                functions[i].setArgIndexes(new int[] {this.collectedExpressions.get(symbol)});
                functions[i].initialize(outputType, new Class<?>[]{symbol.getType()});
            }
        }
    }
	
	static Integer getIndex(Expression ex, LinkedHashMap<Expression, Integer> expressionIndexes) {
		Integer index = expressionIndexes.get(ex);
		if (index == null) {
			index = expressionIndexes.size();
			expressionIndexes.put(ex, index);
		}
		return index;
	}

	static AggregateFunction initAccumulator(AggregateSymbol aggSymbol, 
			RelationalNode node, LinkedHashMap<Expression, Integer> expressionIndexes) {
		int[] argIndexes = new int[aggSymbol.getArgs().length];
		AggregateFunction result = null;
		Expression[] args = aggSymbol.getArgs();
		Class<?>[] inputTypes = new Class[args.length];
		for (int j = 0; j < args.length; j++) {
			inputTypes[j] = args[j].getType();
			argIndexes[j] = getIndex(args[j], expressionIndexes);
		}
		Type function = aggSymbol.getAggregateFunction();
		switch (function) {
		case RANK:
		case DENSE_RANK:
			result = new RankingFunction(function);
			break;
		case ROW_NUMBER: //same as count(*)
		case COUNT:
			result = new Count();
			break;
		case SUM:
			result = new Sum();
			break;
		case AVG:
			result = new Avg();
			break;
		case MIN:
			result = new Min();
			break;
		case MAX:
			result = new Max();
			break;
		case XMLAGG:
			result = new XMLAgg();
			break;
		case ARRAY_AGG:
			result = new ArrayAgg();
			break;                		
		case JSONARRAY_AGG:
			result = new JSONArrayAgg();
			break;
		case TEXTAGG:
			result = new TextAgg((TextLine)args[0]);
			break;
		case STRING_AGG:
			result = new StringAgg(aggSymbol.getType() == DataTypeManager.DefaultDataClasses.BLOB);
			break;     
		case USER_DEFINED:
			result = new UserDefined(aggSymbol.getFunctionDescriptor());
			break;
		default:
			result = new StatsFunction(function);
		}
	    if(aggSymbol.isDistinct()) {
	    	SortingFilter filter = new SortingFilter(result, node.getBufferManager(), node.getConnectionID(), true);
			List<ElementSymbol> elements = createSortSchema(result, inputTypes);
			filter.setElements(elements);
			result = filter;
	    } else if (aggSymbol.getOrderBy() != null) {
	    	int numOrderByItems = aggSymbol.getOrderBy().getOrderByItems().size();
			List<OrderByItem> orderByItems = new ArrayList<OrderByItem>(numOrderByItems);
			List<ElementSymbol> schema = createSortSchema(result, inputTypes);
			argIndexes = Arrays.copyOf(argIndexes, argIndexes.length + numOrderByItems);
			for (ListIterator<OrderByItem> iterator = aggSymbol.getOrderBy().getOrderByItems().listIterator(); iterator.hasNext();) {
				OrderByItem item = iterator.next();
				argIndexes[args.length + iterator.previousIndex()] = getIndex(item.getSymbol(), expressionIndexes);
				ElementSymbol element = new ElementSymbol(String.valueOf(iterator.previousIndex()));
	            element.setType(item.getSymbol().getType());
				schema.add(element);
				OrderByItem newItem = item.clone();
				newItem.setSymbol(element);
				orderByItems.add(newItem);
			}
			SortingFilter filter = new SortingFilter(result, node.getBufferManager(), node.getConnectionID(), false);
			filter.setElements(schema);
			filter.setSortItems(orderByItems);
			result = filter;
		}
		result.setArgIndexes(argIndexes);
		if (aggSymbol.getCondition() != null) {
			result.setConditionIndex(getIndex(aggSymbol.getCondition(), expressionIndexes));
		}
		result.initialize(aggSymbol.getType(), inputTypes);
		return result;
	}

	private static List<ElementSymbol> createSortSchema(AggregateFunction af,
			Class<?>[] inputTypes) {
		List<ElementSymbol> elements = new ArrayList<ElementSymbol>(inputTypes.length);
		int[] filteredArgIndexes = new int[inputTypes.length];
		for (int i = 0; i < inputTypes.length; i++) {
			ElementSymbol element = new ElementSymbol("val" + i); //$NON-NLS-1$
			element.setType(inputTypes[i]);
			elements.add(element);
            filteredArgIndexes[i] = i;
		}
		af.setArgIndexes(filteredArgIndexes);
		return elements;
	}

    AggregateFunction[] getFunctions() {
		return functions;
	}

	public TupleBatch nextBatchDirect()
		throws BlockedException, TeiidComponentException, TeiidProcessingException {

        // Take inputs, evaluate expressions, and build initial tuple source
        if(this.phase == COLLECTION) {
            collectionPhase();
        }

        // If necessary, sort to determine groups (if no group cols, no need to sort)
        if(this.phase == SORT) {
            sortPhase();
        }

        // Walk through the sorted results and for each group, emit a row
        if(this.phase == GROUP) {
            return groupPhase();
        }
        
        this.terminateBatches();
        return pullBatch();
    }
	
	public TupleSource getCollectionTupleSource() {
		final RelationalNode sourceNode = this.getChildren()[0];
		return new ProjectingTupleSource(sourceNode, getEvaluator(elementMap), new ArrayList<Expression>(collectedExpressions.keySet()));
	}
	
	@Override
	protected Collection<? extends LanguageObject> getObjects() {
		return this.getChildren()[0].getOutputElements();
	}

    private void collectionPhase() {
        if(this.orderBy == null) {
            // No need to sort
            this.groupTupleSource = getCollectionTupleSource();
            this.phase = GROUP;
        } else {
        	List<NullOrdering> nullOrdering = new ArrayList<NullOrdering>(orderBy.size());
        	List<Boolean> sortTypes = new ArrayList<Boolean>(orderBy.size());
        	int size = orderBy.size();
        	if (this.removeDuplicates) {
        		//sort on all inputs
        		size = distinctCols;
        	}
        	int[] sortIndexes = new int[size];
        	for (int i = 0; i < size; i++) {
        		if (i < this.orderBy.size()) {
        			OrderByItem item = this.orderBy.get(i);
        			nullOrdering.add(item.getNullOrdering());
        			sortTypes.add(item.isAscending());
        		} else {
        			nullOrdering.add(null);
        			sortTypes.add(OrderBy.ASC);
        		}
        		sortIndexes[i] = i; 
        	}
        	this.indexes = Arrays.copyOf(sortIndexes, orderBy.size());
            this.sortUtility = new SortUtility(getCollectionTupleSource(), removeDuplicates?Mode.DUP_REMOVE_SORT:Mode.SORT, getBufferManager(),
                    getConnectionID(), new ArrayList<Expression>(collectedExpressions.keySet()), sortTypes, nullOrdering, sortIndexes);
            this.phase = SORT;
        }
    }

    private void sortPhase() throws BlockedException, TeiidComponentException, TeiidProcessingException {
        this.sortBuffer = this.sortUtility.sort();
        this.sortBuffer.setForwardOnly(true);
        this.groupTupleSource = this.sortBuffer.createIndexedTupleSource();
        this.phase = GROUP;
    }

    private TupleBatch groupPhase() throws BlockedException, TeiidComponentException, TeiidProcessingException {
        while(true) {

        	if (currentGroupTuple == null) {
        		currentGroupTuple = this.groupTupleSource.nextTuple();
        		if (currentGroupTuple == null) {
        			break;
        		}
        	}
        	
            if(lastRow == null) {
                // First row we've seen
                lastRow = currentGroupTuple;

            } else if(! sameGroup(indexes, currentGroupTuple, lastRow)) {
                // Close old group
                List<Object> row = new ArrayList<Object>(functions.length);
                for(int i=0; i<functions.length; i++) {
                    row.add( functions[i].getResult(getContext()) );
                    functions[i].reset();
                }

                // Reset last tuple
                lastRow = currentGroupTuple;
                
                // Save in output batch
                addBatchRow(row);
                if (this.isBatchFull()) {
                	return pullBatch();
                }
            }

            // Update function accumulators with new row - can throw blocked exception
            updateAggregates(currentGroupTuple);
            currentGroupTuple = null;
        }
        if(lastRow != null || orderBy == null) {
            // Close last group
            List<Object> row = new ArrayList<Object>(functions.length);
            for(int i=0; i<functions.length; i++) {
                row.add( functions[i].getResult(getContext()) );
            }
            
            addBatchRow(row);
        } 

        this.terminateBatches();
        return pullBatch();
    }

	public static boolean sameGroup(int[] indexes, List<?> newTuple, List<?> oldTuple) {
		if (indexes == null) {
			return true;
		}
        return MergeJoinStrategy.compareTuples(newTuple, oldTuple, indexes, indexes, true) == 0;
    }

    private void updateAggregates(List<?> tuple)
    throws TeiidComponentException, TeiidProcessingException {

        for(int i=0; i<functions.length; i++) {
            functions[i].addInput(tuple, getContext());
        }
    }

    public void closeDirect() {
    	if (this.sortBuffer != null) {
    		this.sortBuffer.remove();
    		this.sortBuffer = null;
    	}
    	if (this.sortUtility != null) {
    		this.sortUtility.remove();
    		this.sortUtility = null;
    	}
    }

	protected void getNodeString(StringBuffer str) {
		super.getNodeString(str);
		str.append(orderBy);
		if (outputMapping != null) {
			str.append(outputMapping);
		}
	}

	public GroupingNode clone(){
		GroupingNode clonedNode = new GroupingNode(super.getID());
		super.copyTo(clonedNode);
		clonedNode.removeDuplicates = removeDuplicates;
		clonedNode.outputMapping = outputMapping;
		clonedNode.orderBy = orderBy;
		return clonedNode;
	}

    public PlanNode getDescriptionProperties() {
        // Default implementation - should be overridden
    	PlanNode props = super.getDescriptionProperties();

        if(orderBy != null) {
            int elements = orderBy.size();
            List<String> groupCols = new ArrayList<String>(elements);
            for(int i=0; i<elements; i++) {
                groupCols.add(this.orderBy.get(i).toString());
            }
            props.addProperty(PROP_GROUP_COLS, groupCols);
        }
        props.addProperty(PROP_SORT_MODE, String.valueOf(this.removeDuplicates));

        return props;
    }

}