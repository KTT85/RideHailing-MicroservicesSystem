import { PrismaClient, TripStatus } from "@prisma/client";

const prisma = new PrismaClient();

async function main(): Promise<void> {
  const idempotencyKey = "seed-trip-sample";
  await prisma.trip.upsert({
    where: { idempotencyKey },
    update: {},
    create: {
      passengerId: "11111111-1111-4111-8111-111111111111",
      status: TripStatus.REQUESTED,
      pickup: { lat: 10.7769, lng: 106.7009, address: "Ben Thanh Market" },
      dropoff: { lat: 10.8231, lng: 106.6297, address: "Tan Son Nhat Airport" },
      idempotencyKey,
    },
  });
}

main()
  .catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => prisma.$disconnect());
