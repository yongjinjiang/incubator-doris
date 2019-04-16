// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.optimizer.search;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.doris.optimizer.MultiExpression;
import org.apache.doris.optimizer.OptBinding;
import org.apache.doris.optimizer.OptExpression;
import org.apache.doris.optimizer.OptGroup;
import org.apache.doris.optimizer.base.EnforcerProperty;
import org.apache.doris.optimizer.base.OptCostContext;
import org.apache.doris.optimizer.base.OptimizationContext;
import org.apache.doris.optimizer.base.RequiredPhysicalProperty;
import org.apache.doris.optimizer.operator.OptExpressionHandle;
import org.apache.doris.optimizer.operator.OptOperatorType;
import org.apache.doris.optimizer.operator.OptPatternLeaf;
import org.apache.doris.optimizer.operator.OptPhysical;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * For searching the best plan rooted by the multi expression under the optimization context.
 * <p>
 * +--------------------------------+
 * |                                |  Sub plan is pruned.
 * |       InitalizationState       |------------------> Parent StateMachine
 * |                                |
 * +--------------------------------+
 *                |
 *                |  Sub plan is't pruned.
 *                V
 * +--------------------------------+  Children group are not optimized.
 * |                                |------------------>+
 * |      OptimizingChildGroup      |                   | Child StateMachine
 * |                                |<------------------+
 * +--------------------------------+
 *                |
 *                |  Children Group have been optimized.
 *                V
 * +--------------------------------+
 * |                                |
 * |        AddingEnforcer          |
 * |                                |
 * +--------------------------------+
 *                |
 *                |
 *                V
 * +--------------------------------+
 * |                                |
 * |        OptimizingSelf          |
 * |                                |
 * +--------------------------------+
 *                |
 *                |
 *                V
 * +--------------------------------+
 * |                                |
 * |    CompletingOptimization      |
 * |                                |
 * +--------------------------------+
 *                |
 *                |
 *                V
 *        Parent StateMachine
 */
public class TaskMultiExpressionOptimization extends Task {
    private final static Logger LOG = LogManager.getLogger(TaskMultiExpressionOptimization.class);
    private static OptExpression ENFORCE_PATTERN = OptExpression.create(new OptPatternLeaf());
    private final MultiExpression optMExpr;
    private final OptimizationContext optContext;
    private final List<RequiredPhysicalProperty> requriedPropertiesForChildren;

    private TaskMultiExpressionOptimization(MultiExpression mExpr, OptimizationContext optContext,
                                            Task parent) {
        super(parent);
        this.optMExpr = mExpr;
        this.optContext = optContext;
        this.nextState = new InitalizationState();
        this.requriedPropertiesForChildren = Lists.newArrayList();
    }

    public static void schedule(SearchContext sContext, MultiExpression mExpr,
                                OptimizationContext optContext, Task parent) {
        sContext.schedule(new TaskMultiExpressionOptimization(mExpr, optContext, parent));
    }

    private class InitalizationState extends TaskState {

        @Override
        public void handle(SearchContext sContext) {
            final RequiredPhysicalProperty requestProperty = optContext.getReqdPhyProp();
            if (isUnusedRequiresProperty(requestProperty, sContext)) {
                LOG.info("");
                setFinished();
                return;
            }
            if (canPrune()) {
                setFinished();
                return;
            }
            optMExpr.setStatus(MultiExpression.MEState.Optimizing);
            nextState = new OptimizingChildGroupState();
        }

        /**
         * Forbid to optimize unused MultiExpression and dead cycle.
         * @param reqdProp
         * @return
         */
        private boolean isUnusedRequiresProperty(RequiredPhysicalProperty reqdProp, SearchContext sContext) {
            if (!sContext.getSearchVariables().isExecuteOptimization()) {
                return false;
            }

            if (reqdProp.getOrderProperty().getPropertySpec().isEmpty()
                    && optMExpr.getOp().getType() == OptOperatorType.OP_PHYSICAL_SORT) {
                return true;
            }

            if (reqdProp.getDistributionProperty().getPropertySpec().isAny()
                    && optMExpr.getOp().getType() == OptOperatorType.OP_PHYSICAL_DISTRIBUTION) {
                return true;
            }

            return false;
        }

        private boolean canPrune() {
            return false;
        }
    }

    private class OptimizingChildGroupState extends TaskState {

        @Override
        public void handle(SearchContext sContext) {
            boolean schedulingChildrenTask = false;
            final OptExpressionHandle exprHandle = new OptExpressionHandle(optMExpr);
            exprHandle.deriveProperty();
            for (int i = 0; i < optMExpr.getInputs().size(); i++) {
                final OptGroup childGroup = optMExpr.getInput(i);
                if (!childGroup.isOptimized()) {
                    final OptimizationContext childOptContext =
                            deriveChildOptConext(exprHandle, childGroup, i, sContext);
                    TaskGroupOptimization.schedule(sContext, childGroup, childOptContext,
                            TaskMultiExpressionOptimization.this);
                    schedulingChildrenTask = true;
                }
            }

            if (schedulingChildrenTask) {
                // Scheduling children group TaskMultiExpressionOptimization task.
                return;
            }

            if (sContext.getSearchVariables().isExecuteOptimization()) {
                nextState = new AddingEnforcer();
            } else {
                nextState = new CompletingOptimization();
            }
        }

        private OptimizationContext deriveChildOptConext(
                OptExpressionHandle exprHandle, OptGroup childGroup, int childIndex, SearchContext sContext) {
            RequiredPhysicalProperty property;
            if (sContext.getSearchVariables().isExecuteOptimization()) {
                property = new RequiredPhysicalProperty();
                property.compute(exprHandle, optContext.getReqdPhyProp(), childIndex);
            } else {
                property = RequiredPhysicalProperty.createTestProperty();
            }
            requriedPropertiesForChildren.add(property);
            return new OptimizationContext(childGroup, property);
        }
    }


    private class AddingEnforcer extends TaskState {

        @Override
        public void handle(SearchContext sContext) {
            Preconditions.checkState(
                    requriedPropertiesForChildren.size() == optMExpr.getInputs().size(),
                    "Required properties for children is not enough.");

            for (int i = 0; i < requriedPropertiesForChildren.size(); i++) {
                final RequiredPhysicalProperty requiredChildOptContext = requriedPropertiesForChildren.get(i);
                final OptGroup childGroup = optMExpr.getInput(i);
                final OptimizationContext childBestOptContext = childGroup.lookupBest(requiredChildOptContext);
                Preconditions.checkNotNull(childBestOptContext,
                        "Children is't optimized before parent.");
                optContext.addChildOptContext(childBestOptContext);
            }

            if (checkEnforcerProperty(optMExpr, optContext, sContext)) {
                // There is no necessary to add
                nextState = new OptimizingSelf();
                return;
            }
            // The enforcer has been added and is required.
            nextState = new CompletingOptimization();
        }

        /**
         * Check if enforcer need to be add on the top of MultiExpression.
         * @param multiExpression
         * @param optCtx
         * @param sContext
         * @return
         */
        private boolean checkEnforcerProperty(
                MultiExpression multiExpression,
                OptimizationContext optCtx,
                SearchContext sContext) {
            Preconditions.checkState(multiExpression.getOp().isPhysical(),
                    "multiExpression must be physical.");
            for (OptimizationContext childOptCtx : optCtx.getChildrenOptContext()) {
                if (childOptCtx.getBestMultiExpr() == null) {
                    // Child is't optimized or optimized failly.
                    return false;
                }
            }
            OptCostContext costContext = new OptCostContext(multiExpression, optCtx);
            costContext.setChildrenCtxs(optCtx.getChildrenOptContext());

            OptExpressionHandle exprHandle = new OptExpressionHandle(costContext);
            exprHandle.derivePhysicalProperty();

            OptPhysical physicalOp = (OptPhysical) multiExpression.getOp();
            RequiredPhysicalProperty physicalProperty = optCtx.getReqdPhyProp();

            final EnforcerProperty.EnforceType orderEnforceType =
                    physicalProperty.getEnforcerOrderType(physicalOp, exprHandle);
            final EnforcerProperty.EnforceType distributionType =
                    physicalProperty.getEnforcerDistributionType(physicalOp, exprHandle);

            final OptExpression expr = OptBinding.bind(ENFORCE_PATTERN, exprHandle.getMultiExpr(), null);
            List<OptExpression> enforcerExpressions = Lists.newArrayList();

            physicalProperty.getOrderProperty().appendEnforcers(
                    orderEnforceType,physicalProperty, expr, exprHandle, enforcerExpressions);
            physicalProperty.getDistributionProperty().appendEnforcers(
                    distributionType,physicalProperty, expr, exprHandle, enforcerExpressions);

            for (OptExpression enforcer : enforcerExpressions) {
                sContext.getMemo().copyIn(multiExpression.getGroup(), enforcer);
            }

            return EnforcerProperty.isOptimized(orderEnforceType, distributionType);
        }
    }


    private class OptimizingSelf extends TaskState {

        @Override
        public void handle(SearchContext sContext) {
            final OptCostContext costContext = computeCost();
            optMExpr.getGroup().updateBestCost(optContext, costContext);
            nextState = new CompletingOptimization();
        }

        private OptCostContext computeCost() {
            final OptCostContext context = new OptCostContext(optMExpr, optContext);
            context.compute();
            return context;
        }
    }

    private class CompletingOptimization extends TaskState {

        @Override
        public void handle(SearchContext sContext) {
            optMExpr.setStatus(MultiExpression.MEState.Optimized);
            setFinished();
        }
    }
}