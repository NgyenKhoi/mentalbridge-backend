import { KafkaContainer } from '@testcontainers/kafka';

export class CommunityKafkaContainer extends KafkaContainer {
  constructor(private readonly hostPort: number) {
    super('confluentinc/cp-kafka:7.8.0');
    this.withKraft();
  }
  protected override async beforeContainerCreated(): Promise<void> {
    await super.beforeContainerCreated();
    this.withExposedPorts({ container: 9093, host: this.hostPort });
  }
}
