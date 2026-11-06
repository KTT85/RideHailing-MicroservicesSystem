import { Router } from "express";
import { TripController } from "../controllers/tripController";

/** Builds the REST router for trip resources and lifecycle actions. */
export function createTripRoutes(controller: TripController): Router {
  const router = Router();
  router.post("/", controller.create);
  router.get("/", controller.list);
  router.get("/:id", controller.get);
  router.post("/:id/accept", controller.accept);
  router.post("/:id/reject", controller.reject);
  router.post("/:id/start", controller.start);
  router.post("/:id/complete", controller.complete);
  router.post("/:id/cancel", controller.cancel);
  return router;
}
