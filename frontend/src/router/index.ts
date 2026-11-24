import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import CreateExchangeView from '../views/CreateExchangeView.vue'
import ExchangeView from '../views/ExchangeView.vue'
import SubmitBookView from '../views/SubmitBookView.vue'

export const routes = [
  { path: '/', name: 'home', component: HomeView },
  { path: '/exchanges/new', name: 'create-exchange', component: CreateExchangeView },
  { path: '/exchange/:exchangeCode', name: 'exchange', component: ExchangeView, props: true },
  { path: '/exchange/:exchangeCode/submit', name: 'submit-book', component: SubmitBookView, props: true },
]

export const createAppRouter = (history = createWebHistory()) => createRouter({ history, routes })
