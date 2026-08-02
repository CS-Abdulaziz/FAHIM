package com.example.testandroidenv.agent.contract

interface Planner {
    suspend fun plan(request: PlannerRequest): PlannerDecision
}
