import { Injectable } from '@nestjs/common';
import { PrismaService } from '../../database/prisma.service';

type HealthStatus = {
  status: 'ok';
  service: string;
  timestamp: string;
};

type DatabaseHealthStatus =
  | {
      status: 'ok';
      database: 'connected';
      timestamp: string;
    }
  | {
      status: 'error';
      database: 'disconnected';
      message: string;
      timestamp: string;
    };

@Injectable()
export class HealthService {
  constructor(private readonly prisma: PrismaService) {}

  getHealth(): HealthStatus {
    return {
      status: 'ok',
      service: 'travel-os-api',
      timestamp: new Date().toISOString(),
    };
  }

  async getDatabaseHealth(): Promise<DatabaseHealthStatus> {
    try {
      await this.prisma.$queryRaw`SELECT 1`;

      return {
        status: 'ok',
        database: 'connected',
        timestamp: new Date().toISOString(),
      };
    } catch {
      return {
        status: 'error',
        database: 'disconnected',
        message: 'Database connection failed',
        timestamp: new Date().toISOString(),
      };
    }
  }
}
