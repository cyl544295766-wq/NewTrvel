import 'dotenv/config';
import { PrismaClient } from '@prisma/client';
import bcrypt from 'bcrypt';

const prisma = new PrismaClient();

async function main() {
  await prisma.systemSetting.upsert({
    where: { key: 'app.name' },
    update: { value: 'Travel OS' },
    create: { key: 'app.name', value: 'Travel OS' },
  });

  const username = process.env.SEED_ADMIN_USERNAME ?? 'admin';
  const displayName = process.env.SEED_ADMIN_DISPLAY_NAME ?? 'Admin';
  const password = process.env.SEED_ADMIN_PASSWORD ?? 'admin123';
  const existingAdmin = await prisma.user.findUnique({ where: { username } });
  const passwordHash = await bcrypt.hash(password, 12);

  if (!existingAdmin) {
    await prisma.user.create({
      data: {
        username,
        displayName,
        passwordHash,
      },
    });
    return;
  }

  await prisma.user.update({
    where: { username },
    data: {
      displayName,
      passwordHash,
    },
  });
}

main()
  .then(async () => {
    await prisma.$disconnect();
  })
  .catch(async (error) => {
    console.error(error);
    await prisma.$disconnect();
    process.exit(1);
  });
