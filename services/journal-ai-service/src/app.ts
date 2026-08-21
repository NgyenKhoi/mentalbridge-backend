import "reflect-metadata";
import { Controller, Get, Module } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import { loadConfiguration, type Configuration } from "./configuration.js";
import { JournalModule } from "./journal.js";

@Controller("health") class HealthController { @Get("live") live() { return { status: "ok" }; } }
@Module({ imports: [JournalModule], controllers: [HealthController] }) export class AppModule {}
export const createApplication = async (configuration: Configuration = loadConfiguration()) => { void configuration; const app = await NestFactory.create(AppModule); app.enableShutdownHooks(); return app; };
