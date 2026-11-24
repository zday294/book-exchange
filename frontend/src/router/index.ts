import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import CreateExchangeView from '../views/CreateExchangeView.vue'
import ExchangeView from '../views/ExchangeView.vue'
import SubmitBookView from '../views/SubmitBookView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: HomeView },
  { path: '/exchanges/new', name: 'create-exchange', component: CreateExchangeView },
  { path: '/exchange/:exchangeCode', name: 'exchange', component: ExchangeView, props: true, meta: { requiresName: true } },
  { path: '/exchange/:exchangeCode/submit', name: 'submit-book', component: SubmitBookView, props: true, meta: { requiresName: true } },
]

// Development-only: a gallery for looking at the wrapping papers (not shipped in production builds).
if (import.meta.env.DEV) {
  routes.push({
    path: '/dev/papers',
    name: 'papers-gallery',
    component: () => import('../views/PapersGalleryView.vue'),
  })
}

export const createAppRouter = (history = createWebHistory()) => createRouter({ history, routes })
