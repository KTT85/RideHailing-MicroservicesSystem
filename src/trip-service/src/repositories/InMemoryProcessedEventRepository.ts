/** Contract to replace with durable deduplication at integration time. */
export interface IProcessedEventRepository {
  /** Checks whether all actions for an event have completed. */
  has(eventId: string): Promise<boolean>;
  /** Records completion after all event actions succeed. */
  markProcessed(eventId: string): Promise<void>;
}

export class InMemoryProcessedEventRepository implements IProcessedEventRepository {
  private readonly processedEvents = new Map<string, string>();

  /** Checks an event's completion marker. */
  async has(eventId: string): Promise<boolean> { return this.processedEvents.has(eventId); }

  /** Stores the equivalent of event_id and processed_at. */
  async markProcessed(eventId: string): Promise<void> {
    if (!this.processedEvents.has(eventId)) this.processedEvents.set(eventId, new Date().toISOString());
  }
}
